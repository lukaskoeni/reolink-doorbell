// SPDX-License-Identifier: Apache-2.0
package net.reolinkdoorbell;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One explicitly toggled talk session. Sends microphone audio to go2rtc via RTSP
 * ANNOUNCE/RECORD using G.711 μ-law (PCMU 8 kHz). go2rtc forwards the audio to
 * the Reolink camera over its native RTSP backchannel.
 *
 * go2rtc.yaml must include the Reolink source with audio, e.g.:
 *   streams:
 *     reolink_doorbell:
 *       - reolink://admin:password@192.168.1.50
 */
final class Talkback implements AutoCloseable {
    interface Listener {
        void status(String text, boolean error);
        void microphoneActive();
        void finished();
    }

    private final Context context;
    private final Settings settings;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object gate = new Object();
    private final long deadline;
    private volatile NetworkCleanup.Task socketCleanup;
    private volatile AudioRecord recorder;

    Talkback(Context context, Settings settings, Listener listener) {
        this.context = context;
        this.settings = settings;
        this.listener = listener;
        deadline = System.nanoTime() + settings.talkLimit * 1_000_000_000L;
    }

    void start() {
        Thread t = new Thread(this::run, "reolink-talk");
        t.setDaemon(true);
        t.start();
    }

    private boolean active() { return !closed.get() && System.nanoTime() < deadline; }

    private void run() {
        try {
            if (!settings.talkEnabled) throw new IOException("Two-way audio is disabled in plugin settings");
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Microphone permission not granted — enable it in Android settings");
            if (!active()) return;

            listener.status("Connecting backchannel — tap Talk again to cancel", false);

            URI url = settings.streamUrl;
            String host = url.getHost();
            int port = url.getPort() < 0 ? ("rtsps".equals(url.getScheme()) ? 322 : 554) : url.getPort();

            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 8000);
            final Socket finalSock = sock;
            socketCleanup = new NetworkCleanup.Task(finalSock::close);
            if (!active()) return;

            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String backUrl = url.toASCIIString();
            int cseq = 0;

            // Announce an audio-only SDP with PCMU/8000 (G.711 μ-law).
            // go2rtc accepts this and routes audio to the Reolink backchannel.
            String sdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=Backchannel\r\nt=0 0\r\n"
                + "m=audio 0 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=sendonly\r\n";
            byte[] sdpBytes = sdp.getBytes("UTF-8");

            sendRtsp(out, "ANNOUNCE", backUrl, ++cseq,
                "Content-Type: application/sdp\r\nContent-Length: " + sdpBytes.length + "\r\n", sdpBytes);
            readOk(in, cseq);
            if (!active()) return;

            String trackUrl = backUrl + (backUrl.endsWith("/") ? "track0" : "/track0");
            sendRtsp(out, "SETUP", trackUrl, ++cseq,
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n", null);
            Protocol.Message setupReply = readOk(in, cseq);
            String rtspSession = setupReply.headers.getOrDefault("session", "").split(";")[0].trim();
            if (rtspSession.isEmpty()) throw new IOException("No RTSP session in SETUP response from go2rtc");
            if (!active()) return;

            sendRtsp(out, "RECORD", backUrl, ++cseq,
                "Session: " + rtspSession + "\r\nRange: npt=0-\r\n", null);
            readOk(in, cseq);
            if (!active()) return;

            // Open microphone at 8 kHz to match PCMU/8000.
            final int RATE = 8000;
            final int FRAME_SAMPLES = 160; // 20 ms at 8 kHz
            int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Microphone does not support 8 kHz sample rate");

            AudioRecord capture = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setSampleRate(RATE).build())
                .setBufferSizeInBytes(Math.max(minimum * 2, RATE / 5 * 2)).build();

            synchronized (gate) {
                recorder = capture;
                if (!active()) return;
                if (capture.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IOException("Microphone initialization failed");
                capture.startRecording();
            }
            if (capture.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException("Microphone unavailable or in use by another app");

            listener.microphoneActive();
            listener.status("Microphone active — sending audio", false);

            byte[] pcm = new byte[FRAME_SAMPLES * 2]; // 16-bit mono → 2 bytes/sample
            byte[] ulaw = new byte[FRAME_SAMPLES];
            int sequence = new SecureRandom().nextInt(65536);
            long timestamp = 0;
            int ssrc = new SecureRandom().nextInt();
            sock.setSoTimeout(0);

            while (active()) {
                int n = capture.read(pcm, 0, pcm.length);
                if (n < 0) throw new IOException("Microphone capture failed");
                if (n == 0) { Thread.sleep(5); continue; }

                int samples = n / 2;
                for (int i = 0; i < samples; i++) {
                    short s = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
                    ulaw[i] = encodePcmu(s);
                }

                // 12-byte RTP header + PCMU payload
                byte[] rtp = new byte[12 + samples];
                rtp[0] = (byte) 0x80;
                rtp[1] = 0; // payload type 0 = PCMU
                rtp[2] = (byte) (sequence >>> 8);
                rtp[3] = (byte) sequence;
                rtp[4] = (byte) (timestamp >>> 24);
                rtp[5] = (byte) (timestamp >>> 16);
                rtp[6] = (byte) (timestamp >>> 8);
                rtp[7] = (byte) timestamp;
                rtp[8]  = (byte) (ssrc >>> 24);
                rtp[9]  = (byte) (ssrc >>> 16);
                rtp[10] = (byte) (ssrc >>> 8);
                rtp[11] = (byte) ssrc;
                System.arraycopy(ulaw, 0, rtp, 12, samples);
                sequence = (sequence + 1) & 0xffff;
                timestamp = (timestamp + samples) & 0xffffffffL;

                // RTSP interleaved framing: $ + channel(0) + 2-byte big-endian length + payload
                synchronized (gate) {
                    if (!active()) break;
                    out.write('$');
                    out.write(0);
                    out.write(rtp.length >>> 8);
                    out.write(rtp.length & 0xff);
                    out.write(rtp);
                    out.flush();
                }
            }
            if (!closed.get()) listener.status("Talk time limit reached", false);

        } catch (Exception e) {
            if (!closed.get())
                listener.status(Failures.describe(e, "Backchannel failed; check go2rtc RTSP config and microphone permissions"), true);
        } finally {
            close();
            synchronized (gate) {
                AudioRecord r = recorder;
                recorder = null;
                if (r != null) { try { r.release(); } catch (RuntimeException ignored) {} }
            }
            listener.finished();
        }
    }

    private static void sendRtsp(OutputStream out, String method, String url, int cseq, String extra, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        sb.append("User-Agent: ReolinkDoorbell/1.0\r\n");
        if (extra != null) sb.append(extra);
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (body != null) out.write(body);
        out.flush();
    }

    private static Protocol.Message readOk(InputStream in, int expectedCseq) throws IOException {
        for (int i = 0; i < 256; i++) {
            Protocol.Message m = Protocol.read(in);
            if (m.channel >= 0) continue; // skip stray interleaved frames
            if (m.status < 200 || m.status >= 300)
                throw new IOException("RTSP backchannel rejected (status " + m.status
                    + "); verify go2rtc supports RTSP ANNOUNCE/RECORD for this stream");
            return m;
        }
        throw new IOException("RTSP backchannel: no response received");
    }

    /** ITU-T G.711 μ-law encoding (PCMU). */
    private static byte encodePcmu(short sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = (short) -sample;
        if (sample > 32635) sample = 32635;
        sample += 132;
        int exp = 7;
        for (int mask = 0x4000; (sample & mask) == 0 && exp > 0; exp--, mask >>= 1) {}
        int mantissa = (sample >> (exp + 3)) & 0x0f;
        return (byte) (~(sign | (exp << 4) | mantissa));
    }

    public void close() {
        synchronized (gate) {
            closed.set(true);
            NetworkCleanup.Task s = socketCleanup;
            if (s != null) s.close();
            AudioRecord r = recorder;
            if (r != null) { try { r.stop(); } catch (RuntimeException ignored) {} }
        }
    }
}
