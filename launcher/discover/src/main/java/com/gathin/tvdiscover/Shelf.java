package com.gathin.tvdiscover;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apple TV's top shelf for one app: what that app publishes to Android TV, its Continue watching rows first, then
 * the programmes of its channels (TvContract.PreviewPrograms). With READ_TV_LISTINGS and without
 * ACCESS_ALL_EPG_DATA the system returns every app's searchable rows and refuses a selection, so the table is read
 * whole and filtered here, as WatchNext does.
 */
final class Shelf {
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    /** TvContract aspect ratios: 16:9, 3:2, 4:3 and 1:1 are wide enough for a landscape tile; 2:3 is a poster. */
    private static final int ASPECT_2_3 = 4, ASPECT_MOVIE_POSTER = 5;

    /** The rows last read, by key, for {@link #find}: the launcher asks for their artwork right after a read. */
    private static final Map<String, JSONObject> ROWS = new ConcurrentHashMap<>();

    private Shelf() {
    }

    static List<JSONObject> read(Context ctx, String pkg, int max) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject row : WatchNext.read(ctx, 20)) {
            if (out.size() >= max) break;
            if (!pkg.equals(row.optString("pkg"))) continue;
            try {
                row.put("wide", true);
            } catch (JSONException ignored) {
                // Shown as a landscape tile anyway.
            }
            out.add(row);
        }
        try (Cursor c = ctx.getContentResolver().query(PREVIEW, null, null, null, null)) {
            List<JSONObject> programs = new ArrayList<>();
            while (c != null && c.moveToNext()) {
                if (!pkg.equals(str(c, "package_name"))) continue;
                String intent = str(c, "intent_uri");
                if (intent.isEmpty()) continue;
                JSONObject t = new JSONObject();
                t.put("key", "pp:" + num(c, "_id"));
                t.put("title", str(c, "title"));
                String sub = str(c, "episode_title");
                String season = str(c, "season_display_number"), episode = str(c, "episode_display_number");
                if (!season.isEmpty() && !episode.isEmpty()) sub = "S" + season + " E" + episode + (sub.isEmpty() ? "" : " · " + sub);
                t.put("subtitle", sub);
                t.put("pkg", pkg);
                t.put("intent", intent);
                String poster = str(c, "poster_art_uri");
                if (poster.isEmpty()) poster = str(c, "thumbnail_uri");
                t.put("poster", poster);
                long ratio = c.getColumnIndex("poster_art_aspect_ratio") < 0 ? 0 : num(c, "poster_art_aspect_ratio");
                t.put("wide", ratio != ASPECT_2_3 && ratio != ASPECT_MOVIE_POSTER);
                t.put("progress", 0);
                t.put("weight", num(c, "weight"));
                programs.add(t);
            }
            // The app's own order: higher weight first.
            programs.sort((a, b) -> Long.compare(b.optLong("weight"), a.optLong("weight")));
            for (JSONObject t : programs) {
                if (out.size() >= max) break;
                out.add(t);
            }
        } catch (RuntimeException | JSONException e) {
            // No permission or no TV provider: the shelf shows what Watch Next gave, or nothing.
        }
        for (JSONObject t : out) ROWS.put(t.optString("key"), t);
        return out;
    }

    static JSONObject find(String key) {
        return ROWS.get(key);
    }

    private static long num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0L : c.getLong(i);
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        String s = i < 0 || c.isNull(i) ? null : c.getString(i);
        return s == null ? "" : s;
    }
}
