package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The owner's Home choices (Launcher settings: dock order, apps added to Home, wallpaper, ...) as a file the laptop
 * can copy off the TV: {@code <external files>/backup/home.json}, written when Home is left. A {@code restore/home.json}
 * pushed back is applied when Home next starts, then deleted; with a "city" it also sets TV Weather's city.
 * Only these choices go in it; the add-ons' keys never live in Home.
 */
final class SettingsBackup {
    private static final Uri WEATHER = Uri.parse("content://com.gathin.tvweather/current");

    private SettingsBackup() {
    }

    /** Writes the backup off the main thread; nothing is written when nothing changed. */
    static void exportAsync(Context ctx) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                export(app);
            } catch (Exception e) {
                android.util.Log.w("tvhome", "settings backup", e);
            }
        }, "settings-backup").start();
    }

    private static void export(Context ctx) throws Exception {
        File dir = new File(Config.dir(ctx), "backup");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, ?> e : ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject typed = new JSONObject();
            if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object s : (Set<?>) v) a.put(String.valueOf(s));
                typed.put("t", "set").put("v", a);
            } else if (v instanceof Boolean) {
                typed.put("t", "bool").put("v", v);
            } else if (v instanceof Integer) {
                typed.put("t", "int").put("v", v);
            } else if (v instanceof Long) {
                typed.put("t", "long").put("v", v);
            } else if (v instanceof Float) {
                typed.put("t", "float").put("v", ((Float) v).doubleValue());
            } else {
                typed.put("t", "string").put("v", String.valueOf(v));
            }
            prefs.put(e.getKey(), typed);
        }
        String text = new JSONObject().put("version", 1).put(Config.SETTINGS_NAME, prefs).toString(2);
        File out = new File(dir, "home.json");
        if (out.isFile() && text.equals(new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8))) return;
        File tmp = new File(dir, "home.json.tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(out)) Files.write(out.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** Applies a pushed {@code restore/home.json}, before Home reads its settings. Returns true when one was applied. */
    static boolean importIfPresent(Context ctx) {
        File in = new File(new File(Config.dir(ctx), "restore"), "home.json");
        if (!in.isFile()) return false;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(in.toPath()), StandardCharsets.UTF_8));
            JSONObject prefs = root.optJSONObject(Config.SETTINGS_NAME);
            if (prefs != null) {
                SharedPreferences.Editor ed = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit();
                java.util.Iterator<String> keys = prefs.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    JSONObject typed = prefs.getJSONObject(k);
                    switch (typed.optString("t")) {
                        case "set": {
                            Set<String> s = new HashSet<>();
                            JSONArray a = typed.getJSONArray("v");
                            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                            ed.putStringSet(k, s);
                            break;
                        }
                        case "bool": ed.putBoolean(k, typed.getBoolean("v")); break;
                        case "int": ed.putInt(k, typed.getInt("v")); break;
                        case "long": ed.putLong(k, typed.getLong("v")); break;
                        case "float": ed.putFloat(k, (float) typed.getDouble("v")); break;
                        default: ed.putString(k, typed.optString("v")); break;
                    }
                }
                ed.commit();
            }
            String city = root.optString("city", "");
            if (!city.isEmpty()) {
                // TV Weather looks the name up online: off the main thread.
                new Thread(() -> {
                    try {
                        ctx.getContentResolver().call(WEATHER, "set_city", city, null);
                    } catch (RuntimeException ignored) {
                        // No weather add-on: the city waits for the next restore.
                    }
                }, "restore-city").start();
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("tvhome", "settings restore", e);
            return false;
        } finally {
            if (!in.delete()) in.deleteOnExit();
        }
    }
}
