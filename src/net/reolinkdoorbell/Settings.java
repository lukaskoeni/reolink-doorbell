// SPDX-License-Identifier: Apache-2.0
package net.reolinkdoorbell;

import java.net.URI;
import java.util.Map;

final class Settings {
    final URI streamUrl;
    final String sizing, viewport, controls, autoClose;
    final float horizontal, vertical;
    final int controlSize, talkLimit, autoCloseSeconds;
    final boolean startMuted, muteWhileTalking, talkEnabled;

    Settings(Map<String, Object> m) {
        String raw = text(m, "streamUrl").trim();
        URI u;
        try {
            u = raw.isEmpty() ? URI.create("rtsp://not-configured.invalid/stream") : URI.create(raw);
            String scheme = u.getScheme();
            if (!"rtsp".equals(scheme) && !"rtsps".equals(scheme)) throw new IllegalArgumentException();
            if (u.getHost() == null) throw new IllegalArgumentException();
            if (u.getRawUserInfo() != null) throw new IllegalArgumentException("Credentials in the URL are not supported; use an unauthenticated go2rtc endpoint");
            if (u.getPort() == 0 || u.getPort() > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Stream URL must be an RTSP or RTSPS URL, e.g. rtsp://192.168.1.10:8554/reolink_doorbell");
        }
        streamUrl = u;
        sizing = choice(m, "sizing", "Stretch", "Fit", "Fill", "Stretch");
        viewport = choice(m, "viewport", "Auto", "Auto", "4:3", "16:9", "16:10");
        controls = choice(m, "controls", "Top", "Top", "Bottom", "Left", "Right");
        autoClose = choice(m, "autoClose", "1 minute", "Off", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes");
        autoCloseSeconds = "Off".equals(autoClose) ? 0
            : "30 seconds".equals(autoClose) ? 30
            : "1 minute".equals(autoClose) ? 60
            : "2 minutes".equals(autoClose) ? 120
            : "5 minutes".equals(autoClose) ? 300 : 600;
        horizontal = number(m, "horizontal", 50, 0, 100) / 100f;
        vertical = number(m, "vertical", 50, 0, 100) / 100f;
        controlSize = number(m, "controlSize", 56, 48, 96);
        talkLimit = number(m, "talkLimit", 20, 5, 60);
        startMuted = flag(m, "startMuted", true);
        muteWhileTalking = flag(m, "muteWhileTalking", true);
        talkEnabled = flag(m, "talkEnabled", false);
    }

    void requireStream() {
        if (streamUrl.getHost().endsWith(".invalid"))
            throw new IllegalArgumentException("Set the RTSP stream URL in plugin settings before opening the viewer");
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key); return v instanceof String ? (String) v : "";
    }
    private static boolean flag(Map<String, Object> m, String key, boolean d) {
        return m.get(key) instanceof Boolean ? (Boolean) m.get(key) : d;
    }
    private static int number(Map<String, Object> m, String key, int d, int min, int max) {
        Object v = m.get(key); double n = v instanceof Number ? ((Number) v).doubleValue() : d;
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException("Out-of-range setting: " + key);
        return (int) Math.round(n);
    }
    private static String choice(Map<String, Object> m, String key, String d, String... options) {
        String v = text(m, key); if (v.isEmpty()) return d;
        for (String o : options) if (o.equals(v)) return v;
        throw new IllegalArgumentException("Unsupported setting: " + key);
    }
}
