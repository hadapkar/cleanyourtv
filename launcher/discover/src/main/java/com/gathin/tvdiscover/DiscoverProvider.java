package com.gathin.tvdiscover;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Read-only view of the catalogue for the launcher, which has no network of its own:
 * <ul>
 *   <li>{@code content://com.gathin.tvdiscover/for_you}: the best matches, one row per title;</li>
 *   <li>{@code content://com.gathin.tvdiscover/queue}: titles not rated yet (for the Swipe card's count);</li>
 *   <li>{@code content://com.gathin.tvdiscover/continue}: Android TV's Continue watching rows (see WatchNext);</li>
 *   <li>{@code content://com.gathin.tvdiscover/poster/<key>}: the title's poster as an image file.</li>
 * </ul>
 * A read starts a background refresh when the list is older than {@link Catalog#REFRESH_MS}.
 */
public class DiscoverProvider extends ContentProvider {
    private static final String[] COLUMNS = {"key", "title", "year", "genres", "service", "pkg", "poster"};
    private static final String[] CONTINUE = {"key", "title", "subtitle", "pkg", "intent", "progress", "poster", "engaged"};
    private volatile boolean refreshing;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String what = uri.getLastPathSegment();
        if ("continue".equals(what)) {
            MatrixCursor c = new MatrixCursor(CONTINUE);
            for (JSONObject t : WatchNext.read(getContext(), 6)) {
                String key = t.optString("key");
                c.addRow(new Object[]{key, t.optString("title"), t.optString("subtitle"), t.optString("pkg"),
                        t.optString("intent"), t.optInt("progress"), "content://com.gathin.tvdiscover/poster/" + key,
                        t.optLong("engaged")});
            }
            return c;
        }
        maybeRefresh(false);
        List<JSONObject> list = "queue".equals(what) ? Catalog.queue(getContext())
                : "catalogue".equals(what) ? Catalog.all(getContext())
                : Catalog.forYou(getContext(), 12);
        MatrixCursor c = new MatrixCursor(COLUMNS);
        for (JSONObject t : list) {
            String key = t.optString("key");
            c.addRow(new Object[]{key, t.optString("title"), t.optString("year"), t.optString("genre_names"),
                    t.optString("service"), t.optString("pkg"), "content://com.gathin.tvdiscover/poster/" + key});
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !"poster".equals(seg.get(0))) throw new FileNotFoundException(uri.toString());
        String key = seg.get(1);
        File f;
        if (key.startsWith("wn:")) {
            JSONObject row = WatchNext.find(getContext(), key);
            f = row == null ? null : WatchNext.poster(getContext(), row);
        } else {
            JSONObject t = Catalog.find(getContext(), key);
            f = t == null ? null : Catalog.poster(getContext(), t);
        }
        if (f == null) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("refresh".equals(method)) maybeRefresh(false);
        return Bundle.EMPTY;
    }

    private synchronized void maybeRefresh(boolean force) {
        if (refreshing || (!force && !Catalog.stale(getContext()))) return;
        refreshing = true;
        new Thread(() -> {
            try {
                Catalog.refresh(getContext());
            } finally {
                refreshing = false;
            }
        }, "discover-refresh").start();
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
