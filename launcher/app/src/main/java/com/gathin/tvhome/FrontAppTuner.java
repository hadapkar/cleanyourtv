package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Per-app output, set by the app in front (the key helper's window events), from the theme's config.json:
 * <ul>
 *   <li>{@code "frame_rate_apps": {"app.cricfy.tv": 50, ...}}: while that app is in front, a 1 px accessibility overlay
 *       asks for that refresh rate. WindowManager uses the preferred display mode of the topmost window that has one,
 *       so 25/50 fps cricket goes out at 50 Hz instead of 60 Hz, where every few frames one was shown twice (judder on
 *       pans). Any other app, or Home, removes the overlay: the app or the usual 60 Hz decides again.</li>
 *   <li>{@code "app_volume": true}: the music volume each app was left at comes back when it opens again.</li>
 * </ul>
 */
final class FrontAppTuner {
    /** Windows that are not "the app being watched": Jarvis over it, the assistant, the system's own panels. */
    private static final Set<String> OVER_APP = new HashSet<>(Arrays.asList(
            "com.gathin.tvjarvis", "com.google.android.katniss", "com.android.systemui", "android"));
    private static final String PREFS = "app_volume";

    private final AccessibilityService service;
    private final WindowManager wm;
    private final AudioManager audio;
    private final Map<String, Float> rates;
    private final boolean appVolume;
    private String front = "";
    private View dot;
    private int dotMode;

    private FrontAppTuner(AccessibilityService service, Map<String, Float> rates, boolean appVolume) {
        this.service = service;
        this.rates = rates;
        this.appVolume = appVolume;
        wm = service.getSystemService(WindowManager.class);
        audio = service.getSystemService(AudioManager.class);
    }

    /** Null when the theme asks for neither. */
    static FrontAppTuner create(AccessibilityService service) {
        Map<String, Float> rates = new HashMap<>();
        boolean appVolume = false;
        try {
            File file = new File(Config.dir(service), Config.FILE_NAME);
            if (file.isFile()) {
                JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                JSONObject r = json.optJSONObject("frame_rate_apps");
                if (r != null) {
                    for (Iterator<String> it = r.keys(); it.hasNext(); ) {
                        String pkg = it.next();
                        double hz = r.optDouble(pkg, 0);
                        if (hz >= 23 && hz <= 61) rates.put(pkg, (float) hz);
                    }
                }
                appVolume = json.optBoolean("app_volume", false);
            }
        } catch (Exception e) {
            android.util.Log.w("tvhome", "front app tuner config", e);
        }
        return rates.isEmpty() && !appVolume ? null : new FrontAppTuner(service, rates, appVolume);
    }

    /** An activity of {@code pkg} came to the front. */
    void onFront(String pkg) {
        if (pkg == null || OVER_APP.contains(pkg) || pkg.equals(front)) return;
        String left = front;
        front = pkg;
        if (appVolume && audio != null) {
            SharedPreferences p = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!left.isEmpty()) p.edit().putInt(left, audio.getStreamVolume(AudioManager.STREAM_MUSIC)).apply();
            int saved = p.getInt(pkg, -1);
            if (saved >= 0 && saved != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
                } catch (RuntimeException ignored) {
                    // Do Not Disturb or a fixed-volume output: leave it.
                }
            }
        }
        Float hz = rates.get(pkg);
        setMode(hz == null ? 0 : modeFor(hz));
    }

    /** The display mode at the current resolution closest to {@code hz} (within 0.1 Hz), or 0 for none. */
    private int modeFor(float hz) {
        Display d = wm.getDefaultDisplay();
        Display.Mode now = d.getMode();
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == now.getPhysicalWidth() && m.getPhysicalHeight() == now.getPhysicalHeight()
                    && Math.abs(m.getRefreshRate() - hz) < 0.1f) return m.getModeId();
        }
        return 0;
    }

    private void setMode(int mode) {
        if (mode == dotMode) return;
        dotMode = mode;
        try {
            if (mode == 0) {
                if (dot != null) wm.removeView(dot);
                dot = null;
                return;
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.preferredDisplayModeId = mode;
            if (dot == null) {
                dot = new View(service);
                wm.addView(dot, lp);
            } else {
                wm.updateViewLayout(dot, lp);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("tvhome", "frame rate overlay", e);
            dot = null;
            dotMode = 0;
        }
    }

    /** The helper is going away: back to the usual mode. */
    void release() {
        setMode(0);
    }
}
