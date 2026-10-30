package com.gathin.tvweather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Current weather for one place, fetched by the TV itself from Open-Meteo (free, no key) and served read-only
 * to the launcher. A read refreshes it in the background when it is older than {@link #STALE_MS}; the launcher
 * can also ask with {@code call("refresh")}. Nothing is sent but the place's coordinates.
 */
public class WeatherProvider extends ContentProvider {
    static final String CITY = "Pune";
    static final double LAT = 18.5204;
    static final double LON = 73.8567;

    private static final String PREFS = "weather";
    private static final long STALE_MS = 20 * 60 * 1000L;
    private static final long MIN_GAP_MS = 2 * 60 * 1000L;
    private static final String[] COLUMNS = {"temp_c", "code", "text", "city", "updated", "sunrise"};

    private volatile boolean fetching;
    private volatile long lastAttempt;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SharedPreferences sp = prefs();
        long updated = sp.getLong("updated", 0L);
        if (System.currentTimeMillis() - updated > STALE_MS) refreshAsync();
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (updated > 0) {
            cursor.addRow(new Object[]{sp.getFloat("temp", 0f), sp.getInt("code", 0), sp.getString("text", ""), CITY, updated,
                    sp.getString("sunrise", "")});
        }
        return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("refresh".equals(method)) refreshAsync();
        return Bundle.EMPTY;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private synchronized void refreshAsync() {
        long now = System.currentTimeMillis();
        if (fetching || now - lastAttempt < MIN_GAP_MS) return;
        fetching = true;
        lastAttempt = now;
        new Thread(() -> {
            try {
                fetch();
            } catch (Exception ignored) {
                // Offline or the service is down: keep the last reading; the next read tries again.
            } finally {
                fetching = false;
            }
        }, "weather-fetch").start();
    }

    private void fetch() throws Exception {
        URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                + "&current=temperature_2m,weather_code&daily=sunrise&forecast_days=1&timezone=auto");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject json = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            JSONObject cur = json.getJSONObject("current");
            int code = cur.getInt("weather_code");
            // "2026-10-30T06:35" in the place's own time: keep "06:35".
            JSONObject daily = json.optJSONObject("daily");
            String sunrise = daily == null ? "" : daily.getJSONArray("sunrise").optString(0, "");
            if (sunrise.length() >= 16) sunrise = sunrise.substring(11, 16);
            prefs().edit().putFloat("temp", (float) cur.getDouble("temperature_2m")).putInt("code", code)
                    .putString("text", describe(code)).putString("sunrise", sunrise)
                    .putLong("updated", System.currentTimeMillis()).apply();
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather interpretation codes, in a few words. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
