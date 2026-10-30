package com.gathin.tvdiscover;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * The owner's own data in TV Discover, for the laptop's backup: Swipe ratings (liked, disliked, the genre weights
 * they built) and My list. Written to {@code <external files>/backup.json} on "export"; "restore" reads
 * {@code <external files>/restore.json} and deletes it. The TMDB token and the cached catalogue are never included.
 */
final class Backup {
    private static final String[] SETS = {"liked", "disliked"};
    private static final String[] STRINGS = {"weights", "mylist"};

    private Backup() {
    }

    static boolean export(Context ctx) {
        try {
            SharedPreferences p = Catalog.prefs(ctx);
            JSONObject out = new JSONObject().put("version", 1);
            for (String k : SETS) out.put(k, new JSONArray(p.getStringSet(k, new HashSet<>())));
            for (String k : STRINGS) out.put(k, p.getString(k, ""));
            File f = new File(ctx.getExternalFilesDir(null), "backup.json");
            Files.write(f.toPath(), out.toString(2).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean restore(Context ctx) {
        File f = new File(ctx.getExternalFilesDir(null), "restore.json");
        if (!f.isFile()) return false;
        try {
            JSONObject in = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            SharedPreferences.Editor ed = Catalog.prefs(ctx).edit();
            for (String k : SETS) {
                JSONArray a = in.optJSONArray(k);
                if (a == null) continue;
                Set<String> s = new HashSet<>();
                for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                ed.putStringSet(k, s);
            }
            for (String k : STRINGS) if (in.has(k)) ed.putString(k, in.getString(k));
            return ed.commit();
        } catch (Exception e) {
            return false;
        } finally {
            if (!f.delete()) f.deleteOnExit();
        }
    }
}
