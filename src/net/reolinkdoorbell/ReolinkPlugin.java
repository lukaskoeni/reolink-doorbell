// SPDX-License-Identifier: Apache-2.0
package net.reolinkdoorbell;

import android.content.Context;
import android.view.View;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import me.jxl.kiosk.plugins.*;

public final class ReolinkPlugin implements KioskPlugin {
    private volatile PluginHost host;
    private volatile Settings settings;
    private volatile CameraView view;
    private final AtomicInteger generation = new AtomicInteger();

    public void start(PluginHost host, Map<String, Object> values) {
        this.host = host;
        settings = new Settings(values);
        status("Open Reolink doorbell to start the stream");
    }

    public void configure(Map<String, Object> values) {
        settings = new Settings(values);
        hide();
        status("Settings saved");
    }

    public void execute(String command, Map<String, Object> arguments) {
        if ("hide".equals(command)) { hide(); return; }
        if (!"show".equals(command)) throw new IllegalArgumentException("Unknown command: " + command);
        Settings snapshot = settings;
        try { snapshot.requireStream(); } catch (Exception e) {
            status(Failures.describe(e, "Configure the RTSP stream URL in plugin settings before opening the viewer"));
            return;
        }
        hide();
        final int token = generation.get();
        PluginHost h = host;
        if (h == null) return;
        h.showOverlay("camera", OverlaySpec.fullScreen().closeOnBack(true), new OverlayFactory() {
            public View create(Context context, KsTheme theme) {
                final CameraView[] owner = new CameraView[1];
                CameraView created = new CameraView(context, theme, snapshot,
                    () -> { if (generation.get() == token && view == owner[0]) hide(); },
                    reason -> {
                        if (generation.get() == token && view == owner[0]) {
                            if (!reason.isEmpty()) status(reason);
                            hide();
                        }
                    });
                owner[0] = created;
                if (generation.get() != token || host == null) { created.dispose(); return created; }
                view = created;
                return created;
            }
            public void onDestroy(View destroyed) {
                if (destroyed instanceof CameraView) ((CameraView) destroyed).dispose();
                if (view == destroyed) view = null;
            }
            public void onThemeChanged(View changed, KsTheme theme) {
                if (changed instanceof CameraView) ((CameraView) changed).applyTheme(theme);
            }
        });
    }

    private void hide() {
        generation.incrementAndGet();
        CameraView current = view;
        view = null;
        if (current != null) { current.stopTransports(); current.post(current::dispose); }
        PluginHost h = host;
        if (h != null) h.hideOverlay("camera");
    }

    private void status(String message) {
        PluginHost h = host;
        if (h == null) return;
        h.status(DisplayText.limit(message, 1000), false);
    }

    public void onEvent(String event, Map<String, Object> payload) {
        if ("overlay.closed".equals(event) && "camera".equals(payload.get("key")) && view != null) hide();
    }

    public void stop() {
        generation.incrementAndGet();
        host = null;
        CameraView current = view;
        view = null;
        if (current != null) { current.stopTransports(); current.post(current::dispose); }
    }
}
