package com.gathin.tvjarvis;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The general things Jarvis can do on the TV, each covering many ways of asking: open a settings page, control
 * what is playing, move around (Home, back, apps, switcher), sleep, screensaver, reminders, find a title, My list,
 * and the live context sent with every question. Each returns the line to show, or null when it has nothing to say.
 */
final class Actions {
    private static final String LAUNCHER = "com.gathin.tvhome";
    private static final Uri DISCOVER = Uri.parse("content://com.gathin.tvdiscover");
    private static final Uri WEATHER = Uri.parse("content://com.gathin.tvweather/current");

    private Actions() {
    }

    // ---- 7: settings pages -------------------------------------------------------------------------

    static String settings(Context ctx, String page) {
        String action;
        switch (page) {
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
            case "apps": action = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "storage": action = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
            case "date": action = Settings.ACTION_DATE_SETTINGS; break;
            case "language": action = Settings.ACTION_LOCALE_SETTINGS; break;
            case "network": action = Settings.ACTION_WIRELESS_SETTINGS; break;
            case "accessibility": action = Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "about": action = Settings.ACTION_DEVICE_INFO_SETTINGS; break;
            default: action = Settings.ACTION_SETTINGS; break;
        }
        if (open(ctx, systemHandler(ctx, action))) return null;
        return open(ctx, systemHandler(ctx, Settings.ACTION_SETTINGS)) ? null : "This TV has no such settings page.";
    }

    /** The action answered by a system app, so a sideloaded app claiming it too never brings up a chooser. */
    private static Intent systemHandler(Context ctx, String action) {
        Intent i = new Intent(action);
        for (ResolveInfo r : ctx.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)) {
            if ((r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                return new Intent(action).setClassName(r.activityInfo.packageName, r.activityInfo.name);
            }
        }
        return null;
    }

    // ---- 8: what is playing --------------------------------------------------------------------------

    /** The app playing now (or last), through the media sessions Jarvis may see as a notification listener. */
    static MediaController playing(Context ctx) {
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            if (msm == null) return null;
            List<MediaController> all = msm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            MediaController paused = null;
            for (MediaController c : all) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return c;
                if (paused == null) paused = c;
            }
            return paused;
        } catch (SecurityException e) {
            return null;   // media access not granted from the shell yet
        }
    }

    static String media(Context ctx, String command, int seconds) {
        MediaController c = playing(ctx);
        if (c == null) return "Nothing is playing that I can control.";
        MediaController.TransportControls t = c.getTransportControls();
        PlaybackState s = c.getPlaybackState();
        long pos = s == null ? 0 : s.getPosition()
                + (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        int step = (seconds > 0 ? seconds : 10) * 1000;
        switch (command) {
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            case "toggle":
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
                break;
            case "next": t.skipToNext(); break;
            case "previous": t.skipToPrevious(); break;
            case "stop": t.stop(); break;
            case "rewind": t.seekTo(Math.max(0, pos - step)); break;
            case "forward": t.seekTo(pos + step); break;
            default: return "I can play, pause, skip or seek.";
        }
        return null;
    }

    // ---- 9, 10, 11: the launcher's commands --------------------------------------------------------------

    /** home, library, switcher, screensaver, sleep, sleep_in (minutes; 0 cancels). Sent only to the launcher. */
    static boolean launcher(Context ctx, String command, int minutes) {
        Intent i = new Intent("com.gathin.tvhome.COMMAND").setPackage(LAUNCHER).putExtra("command", command)
                .putExtra("minutes", minutes);
        try {
            ctx.sendBroadcast(i, null);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- 12: reminders -----------------------------------------------------------------------------------

    /** minutes from now, or time "HH:mm" (today, or tomorrow when it has passed). */
    static String reminder(Context ctx, int minutes, String time, String text) {
        long when;
        if (time != null && time.matches("\\d{1,2}:\\d{2}")) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            String[] hm = time.split(":");
            c.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
            c.set(java.util.Calendar.MINUTE, Integer.parseInt(hm[1]));
            c.set(java.util.Calendar.SECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_MONTH, 1);
            when = c.getTimeInMillis();
        } else if (minutes > 0) {
            when = System.currentTimeMillis() + minutes * 60_000L;
        } else {
            return "When should I remind you?";
        }
        String what = text == null || text.trim().isEmpty() ? "Reminder" : text.trim();
        Reminders.add(ctx, when, what);
        return "I'll remind you at " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(when)) + ".";
    }

    // ---- 13, 14: titles and My list (through TV Discover) ---------------------------------------------

    /** The title's details and where it streams, or null. Blocking (TMDB). */
    static JSONObject lookup(Context ctx, String title) {
        return discover(ctx, "find", title);
    }

    static JSONObject listAdd(Context ctx, String title) {
        return discover(ctx, "list_add", title);
    }

    static int listRemove(Context ctx, String title) {
        try {
            Bundle b = ctx.getContentResolver().call(DISCOVER, "list_remove", title, null);
            return b == null ? 0 : b.getInt("removed");
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** {title, pkg} for each My list title that streams here. */
    static JSONArray myList(Context ctx) {
        JSONArray out = new JSONArray();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://com.gathin.tvdiscover/mylist"), null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                out.put(new JSONObject().put("title", c.getString(c.getColumnIndexOrThrow("title")))
                        .put("app", c.getString(c.getColumnIndexOrThrow("pkg"))).put("why", c.getString(c.getColumnIndexOrThrow("service"))));
            }
        } catch (Exception ignored) {
            // TV Discover missing.
        }
        return out;
    }

    private static JSONObject discover(Context ctx, String method, String arg) {
        try {
            Bundle b = ctx.getContentResolver().call(DISCOVER, method, arg, null);
            String json = b == null ? null : b.getString("json");
            return json == null ? null : new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 15: what the TV knows right now --------------------------------------------------------------

    /** Date and time, weather and what is playing, so answers about "now" are right. */
    static String context(Context ctx) {
        StringBuilder s = new StringBuilder();
        s.append("Now: ").append(new SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(new Date()));
        try (Cursor c = ctx.getContentResolver().query(WEATHER, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                s.append("\nWeather in ").append(c.getString(c.getColumnIndexOrThrow("city"))).append(": ")
                        .append(Math.round(c.getFloat(c.getColumnIndexOrThrow("temp_c")))).append("°C, ")
                        .append(c.getString(c.getColumnIndexOrThrow("text")));
                int sr = c.getColumnIndex("sunrise");
                if (sr >= 0 && !c.isNull(sr)) s.append(", sunrise ").append(c.getString(sr));
            }
        } catch (RuntimeException ignored) {
            // No weather add-on.
        }
        MediaController m = playing(ctx);
        if (m != null) {
            String app = m.getPackageName();
            try {
                PackageManager pm = ctx.getPackageManager();
                app = pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // Keep the package name.
            }
            MediaMetadata md = m.getMetadata();
            CharSequence title = md == null ? null : md.getText(MediaMetadata.METADATA_KEY_TITLE);
            PlaybackState st = m.getPlaybackState();
            boolean on = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            s.append("\n").append(on ? "Playing" : "Paused").append(" in ").append(app)
                    .append(title == null ? "" : ": " + title);
        }
        return s.toString();
    }

    private static boolean open(Context ctx, Intent i) {
        if (i == null) return false;
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
