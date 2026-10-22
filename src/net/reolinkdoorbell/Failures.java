// SPDX-License-Identifier: Apache-2.0
package net.reolinkdoorbell;

final class Failures {
    private Failures() {}
    // Network/decoder exceptions may embed secret stream URLs. Never display raw messages.
    static String describe(Exception e, String fallback) {
        if (e instanceof javax.net.ssl.SSLException) return "TLS verification failed; check the RTSPS certificate";
        if (e instanceof java.net.SocketTimeoutException) return "Connection timed out; stopped audio and video";
        if (e instanceof java.net.UnknownHostException) return "Configured hostname could not be resolved";
        if (e instanceof SecurityException) return "Microphone access denied — grant Kiosk Satellite microphone access in Android settings";
        if (e instanceof IllegalArgumentException) {
            String m = e.getMessage();
            if (m != null && (m.startsWith("Set the RTSP") || m.startsWith("Stream URL must") || m.startsWith("Out-of-range setting") || m.startsWith("Unsupported setting"))) return m;
        }
        StackTraceElement[] trace = e.getStackTrace();
        if (e.getClass() == java.io.IOException.class && trace.length > 0
                && trace[0].getClassName().matches("net\\.reolinkdoorbell\\.(Protocol(?:\\$(?:H264|H265))?|Sdp|StreamPlayer|StreamPlayer\\$Decoder|Talkback|VideoParameters|VideoParameters\\$Bits|VideoDecoder)")) return e.getMessage();
        return fallback;
    }
}
