package com.gathin.tvdiscover;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Android TV's Continue watching row (TvContract.WatchNextPrograms), as other apps publish it. Needs
 * READ_TV_LISTINGS (granted from the shell at install). Without ACCESS_ALL_EPG_DATA the system returns only
 * rows marked searchable and refuses a selection, so the whole table is read and filtered here
 * (docs/watch-next.md). Posters are often https, which is why this lives in the add-on with the network.
 */
final class WatchNext {
    private static final Uri TABLE = Uri.parse("content://android.media.tv/watch_next_program");
    private static final long MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;

    private WatchNext() {
    }

    /** Recent rows, newest first, finished ones left out. Also remembered for {@link #poster}. */
    static List<JSONObject> read(Context ctx, int max) {
        List<JSONObject> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Cursor c = ctx.getContentResolver().query(TABLE, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                long engaged = num(c, "last_engagement_time_utc_millis");
                if (engaged > 0 && now - engaged > MAX_AGE_MS) continue;
                long pos = num(c, "last_playback_position_millis"), dur = num(c, "duration_millis");
                if (dur > 0 && pos > dur * 0.95) continue;
                String intent = str(c, "intent_uri"), pkg = str(c, "package_name");
                if (intent.isEmpty() || pkg.isEmpty()) continue;
                JSONObject t = new JSONObject();
                t.put("key", "wn:" + num(c, "_id"));
                t.put("title", str(c, "title"));
                String season = str(c, "season_display_number"), episode = str(c, "episode_display_number");
                String sub = str(c, "episode_title");
                if (!season.isEmpty() && !episode.isEmpty()) sub = "S" + season + " E" + episode + (sub.isEmpty() ? "" : " · " + sub);
                t.put("subtitle", sub);
                t.put("pkg", pkg);
                t.put("intent", intent);
                t.put("poster", str(c, "poster_art_uri"));
                t.put("progress", dur > 0 ? (int) (100 * pos / dur) : 0);
                t.put("engaged", engaged);
                out.add(t);
            }
        } catch (RuntimeException | JSONException e) {
            // No permission yet, or no TV provider: nothing to continue.
        }
        out.sort((a, b) -> Long.compare(b.optLong("engaged"), a.optLong("engaged")));
        List<JSONObject> list = out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
        Catalog.prefs(ctx).edit().putString("watch_next", new JSONArray(list).toString()).apply();
        return list;
    }

    static JSONObject find(Context ctx, String key) {
        try {
            JSONArray a = new JSONArray(Catalog.prefs(ctx).getString("watch_next", "[]"));
            for (int i = 0; i < a.length(); i++) if (key.equals(a.getJSONObject(i).optString("key"))) return a.getJSONObject(i);
        } catch (JSONException ignored) {
            // Treated as not found.
        }
        return null;
    }

    /** The row's artwork on disk, fetched from https or copied from the publishing app's own provider. */
    static File poster(Context ctx, JSONObject row) {
        String src = row.optString("poster");
        if (src.isEmpty()) return null;
        File dir = new File(ctx.getCacheDir(), "watchnext");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, row.optString("key").replace(':', '_') + "_" + Integer.toHexString(src.hashCode()) + ".img");
        if (f.length() > 0) return f;
        File tmp = new File(dir, f.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (src.startsWith("https://")) {
                HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) {
                    copy(in, out);
                } finally {
                    c.disconnect();
                }
            } else if (src.startsWith("content://") || src.startsWith("android.resource://")) {
                try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(src))) {
                    if (in == null) throw new IOException(src);
                    copy(in, out);
                }
            } else {
                throw new IOException("unsupported poster " + src);
            }
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            return null;
        }
        return tmp.renameTo(f) ? f : null;
    }

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
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
