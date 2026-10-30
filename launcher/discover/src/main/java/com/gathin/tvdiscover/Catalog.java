package com.gathin.tvdiscover;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What is streaming in India on the apps this TV has, from TMDB, and what the owner thinks of it.
 *
 * A refresh asks TMDB's discover endpoint once per streaming service and per kind (film, series) and keeps
 * the titles with the app that streams them. Swiping a title right or left adds or takes a point from each of
 * its genres; "For you" ranks the unrated and liked titles by those points, then popularity. Everything is
 * stored on the TV in this add-on's private storage; the only thing sent is the TMDB request itself.
 */
final class Catalog {
    private static final String PREFS = "discover";
    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/w342";
    static final long REFRESH_MS = 6 * 60 * 60 * 1000L;

    /** TMDB provider names (contains, lower case) to the Android TV app that plays them. */
    private static final String[][] SERVICES = {
            {"netflix", "com.netflix.ninja", "Netflix"},
            {"prime video", "com.amazon.amazonvideo.livingroom", "Prime Video"},
            {"hotstar", "in.startv.hotstar", "JioHotstar"},
            {"sony", "com.sonyliv", "SonyLIV"},
    };

    private static final Object LOCK = new Object();

    private Catalog() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String token(Context ctx) {
        return prefs(ctx).getString("token", "");
    }

    static void setToken(Context ctx, String token) {
        prefs(ctx).edit().putString("token", token.trim()).putLong("fetched", 0L).apply();
    }

    static boolean stale(Context ctx) {
        return System.currentTimeMillis() - prefs(ctx).getLong("fetched", 0L) > REFRESH_MS;
    }

    // ---- titles ------------------------------------------------------------------------------------

    /** Every known title, keyed "movie:123" / "tv:456", in TMDB's popularity order. */
    static List<JSONObject> all(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString("titles", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (JSONException ignored) {
            // A damaged list is the same as none; the next refresh writes a new one.
        }
        return out;
    }

    /** Liked or unrated titles, best match first. */
    static List<JSONObject> forYou(Context ctx, int max) {
        Map<String, Integer> weights = weights(ctx);
        Set<String> disliked = set(ctx, "disliked");
        List<JSONObject> list = new ArrayList<>();
        for (JSONObject t : all(ctx)) if (!disliked.contains(t.optString("key"))) list.add(t);
        Set<String> liked = set(ctx, "liked");
        list.sort((a, b) -> Double.compare(score(b, weights, liked), score(a, weights, liked)));
        return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }

    /** Titles not rated yet, most popular first, for the Swipe screen. */
    static List<JSONObject> queue(Context ctx) {
        Set<String> rated = set(ctx, "liked");
        rated.addAll(set(ctx, "disliked"));
        List<JSONObject> list = new ArrayList<>();
        for (JSONObject t : all(ctx)) if (!rated.contains(t.optString("key"))) list.add(t);
        return list;
    }

    private static double score(JSONObject t, Map<String, Integer> weights, Set<String> liked) {
        double s = Math.log1p(t.optDouble("popularity", 0));
        JSONArray g = t.optJSONArray("genres");
        if (g != null) for (int i = 0; i < g.length(); i++) {
            Integer w = weights.get(String.valueOf(g.optInt(i)));
            if (w != null) s += 1.5 * w;
        }
        if (liked.contains(t.optString("key"))) s += 2;
        return s;
    }

    // ---- ratings -----------------------------------------------------------------------------------

    static void rate(Context ctx, JSONObject title, boolean like) {
        synchronized (LOCK) {
            String key = title.optString("key");
            Set<String> liked = set(ctx, "liked"), disliked = set(ctx, "disliked");
            (like ? liked : disliked).add(key);
            (like ? disliked : liked).remove(key);
            Map<String, Integer> weights = weights(ctx);
            JSONArray g = title.optJSONArray("genres");
            if (g != null) for (int i = 0; i < g.length(); i++) {
                String id = String.valueOf(g.optInt(i));
                Integer old = weights.get(id);
                weights.put(id, (old == null ? 0 : old) + (like ? 1 : -1));
            }
            JSONObject w = new JSONObject();
            try {
                for (Map.Entry<String, Integer> e : weights.entrySet()) w.put(e.getKey(), e.getValue());
            } catch (JSONException ignored) {
                // Keys are genre ids and values small ints; put cannot fail here.
            }
            prefs(ctx).edit().putStringSet("liked", liked).putStringSet("disliked", disliked)
                    .putString("weights", w.toString()).apply();
        }
    }

    static int ratedCount(Context ctx) {
        return set(ctx, "liked").size() + set(ctx, "disliked").size();
    }

    private static Set<String> set(Context ctx, String name) {
        return new HashSet<>(prefs(ctx).getStringSet(name, new HashSet<>()));
    }

    private static Map<String, Integer> weights(Context ctx) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            JSONObject w = new JSONObject(prefs(ctx).getString("weights", "{}"));
            for (java.util.Iterator<String> it = w.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, w.getInt(k));
            }
        } catch (JSONException ignored) {
            // Start again from neutral.
        }
        return out;
    }

    // ---- refresh -----------------------------------------------------------------------------------

    /** Fetches a new title list. Blocking; call off the main thread. Returns false without a token or on failure. */
    static boolean refresh(Context ctx) {
        String token = token(ctx);
        if (token.isEmpty()) return false;
        try {
            Map<String, String[]> services = services(token);   // TMDB provider id -> {package, name}
            Map<String, String> genreNames = new LinkedHashMap<>();
            for (String kind : new String[]{"movie", "tv"}) {
                JSONArray g = get(token, "/genre/" + kind + "/list?language=en").getJSONArray("genres");
                for (int i = 0; i < g.length(); i++) genreNames.put(String.valueOf(g.getJSONObject(i).getInt("id")),
                        g.getJSONObject(i).getString("name"));
            }
            Map<String, JSONObject> titles = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> s : services.entrySet()) {
                for (String kind : new String[]{"movie", "tv"}) {
                    JSONArray results = get(token, "/discover/" + kind + "?watch_region=IN&with_watch_providers="
                            + s.getKey() + "&sort_by=popularity.desc&language=en-IN&page=1").getJSONArray("results");
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject r = results.getJSONObject(i);
                        if (r.optString("poster_path", "").isEmpty() || r.isNull("poster_path")) continue;
                        String key = kind + ":" + r.getInt("id");
                        if (titles.containsKey(key)) continue;
                        JSONObject t = new JSONObject();
                        t.put("key", key);
                        t.put("title", r.optString(kind.equals("movie") ? "title" : "name"));
                        String date = r.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
                        t.put("year", date.length() >= 4 ? date.substring(0, 4) : "");
                        t.put("overview", r.optString("overview"));
                        t.put("poster", r.getString("poster_path"));
                        t.put("popularity", r.optDouble("popularity", 0));
                        t.put("rating", r.optDouble("vote_average", 0));
                        JSONArray ids = r.optJSONArray("genre_ids");
                        t.put("genres", ids == null ? new JSONArray() : ids);
                        StringBuilder names = new StringBuilder();
                        if (ids != null) for (int k = 0; k < ids.length() && k < 3; k++) {
                            String n = genreNames.get(String.valueOf(ids.optInt(k)));
                            if (n == null) continue;
                            if (names.length() > 0) names.append(" · ");
                            names.append(n);
                        }
                        t.put("genre_names", names.toString());
                        t.put("pkg", s.getValue()[0]);
                        t.put("service", s.getValue()[1]);
                        titles.put(key, t);
                    }
                }
            }
            if (titles.isEmpty()) return false;
            List<JSONObject> list = new ArrayList<>(titles.values());
            list.sort((a, b) -> Double.compare(b.optDouble("popularity"), a.optDouble("popularity")));
            prefs(ctx).edit().putString("titles", new JSONArray(list).toString())
                    .putLong("fetched", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The services this TV has an app for, as TMDB provider ids for India. */
    private static Map<String, String[]> services(String token) throws IOException, JSONException {
        Map<String, String[]> out = new LinkedHashMap<>();
        JSONArray all = get(token, "/watch/providers/movie?watch_region=IN&language=en").getJSONArray("results");
        for (String[] svc : SERVICES) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject p = all.getJSONObject(i);
                String name = p.optString("provider_name").toLowerCase(Locale.ROOT);
                // "Amazon Prime Video", but not "...with Ads" or channel add-ons sold through Prime.
                if (name.contains(svc[0]) && !name.contains("channel") && !name.contains("with ads")) {
                    out.put(String.valueOf(p.getInt("provider_id")), new String[]{svc[1], svc[2]});
                    break;
                }
            }
        }
        return out;
    }

    private static JSONObject get(String token, String path) throws IOException, JSONException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return getOnce(token, path);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        throw last != null ? last : new IOException(path);
    }

    private static JSONObject getOnce(String token, String path) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    // ---- posters -----------------------------------------------------------------------------------

    /** The poster on disk, downloaded first if needed. Blocking. Null if it cannot be had. */
    static File poster(Context ctx, JSONObject title) {
        String path = title.optString("poster");
        String key = title.optString("key").replace(':', '_');
        if (path.isEmpty() || key.isEmpty()) return null;
        File dir = new File(ctx.getCacheDir(), "posters");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, key + ".jpg");
        if (f.length() > 0) return f;
        File tmp = new File(dir, key + ".part");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + path).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(readAll(in));
            } finally {
                c.disconnect();
            }
            return tmp.renameTo(f) ? f : null;
        } catch (IOException e) {
            tmp.delete();
            return null;
        }
    }

    static JSONObject find(Context ctx, String key) {
        for (JSONObject t : all(ctx)) if (t.optString("key").equals(key)) return t;
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
