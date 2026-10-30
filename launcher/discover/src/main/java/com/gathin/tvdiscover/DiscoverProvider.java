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
 *   <li>{@code content://com.gathin.tvdiscover/shelf/<package>}: that app's own rows, for Home's top shelf (Shelf);</li>
 *   <li>{@code content://com.gathin.tvdiscover/cricket}: live and recent cricket scores (Cricket);</li>
 *   <li>{@code content://com.gathin.tvdiscover/poster/<key>}: the title's poster as an image file.</li>
 * </ul>
 * A read starts a background refresh when the list is older than {@link Catalog#REFRESH_MS}.
 */
public class DiscoverProvider extends ContentProvider {
    private static final String[] COLUMNS = {"key", "title", "year", "genres", "service", "pkg", "poster"};
    private static final String[] CONTINUE = {"key", "title", "subtitle", "pkg", "intent", "progress", "poster", "engaged"};
    private static final String[] SHELF = {"key", "title", "subtitle", "pkg", "intent", "progress", "poster", "wide"};
    /** priority: 3 India playing, 2 IPL, 1 another international, 0 the rest; batting: 1 or 2 for the side in, else 0. */
    private static final String[] CRICKET = {"team1", "score1", "team2", "score2", "batting", "priority", "live"};
    private volatile boolean refreshing;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String what = uri.getLastPathSegment();
        List<String> seg = uri.getPathSegments();
        if (seg.size() == 2 && "shelf".equals(seg.get(0))) {
            MatrixCursor c = new MatrixCursor(SHELF);
            for (JSONObject t : Shelf.read(getContext(), seg.get(1), 10)) {
                String key = t.optString("key");
                c.addRow(new Object[]{key, t.optString("title"), t.optString("subtitle"), t.optString("pkg"),
                        t.optString("intent"), t.optInt("progress"), "content://com.gathin.tvdiscover/poster/" + key,
                        t.optBoolean("wide", true) ? 1 : 0});
            }
            return c;
        }
        if ("cricket".equals(what)) {
            MatrixCursor c = new MatrixCursor(CRICKET);
            for (JSONObject m : Cricket.matches(getContext())) {
                c.addRow(new Object[]{m.optString("team1"), m.optString("score1"), m.optString("team2"),
                        m.optString("score2"), m.optInt("batting"), m.optInt("priority"), m.optInt("live")});
            }
            return c;
        }
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
                : "mylist".equals(what) ? Catalog.myList(getContext())
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
        if (key.startsWith("pp:")) {
            JSONObject row = Shelf.find(key);
            f = row == null ? null : WatchNext.poster(getContext(), row);
        } else if (key.startsWith("wn:")) {
            JSONObject row = WatchNext.find(getContext(), key);
            if (row == null) row = Shelf.find(key);   // a shelf row past the Continue card's six
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
        if ("refresh".equals(method)) {
            maybeRefresh(false);
            return Bundle.EMPTY;
        }
        // Backup and restore of the owner's taste and My list: from the laptop's ADB shell only.
        if ("export".equals(method) || "restore".equals(method)) {
            // The shell's uid (2000): Android does not name the calling package for a call() from `content call`.
            if (android.os.Binder.getCallingUid() != 2000) return null;
            Bundle out = new Bundle();
            out.putBoolean("ok", "export".equals(method) ? Backup.export(getContext()) : Backup.restore(getContext()));
            return out;
        }
        // Lookups and list changes come from Jarvis only; reading stays open to the launcher.
        if (!"com.gathin.tvjarvis".equals(getCallingPackage())) return null;
        Bundle out = new Bundle();
        switch (method) {
            case "find": {
                JSONObject t = Catalog.lookup(getContext(), arg);
                if (t != null) out.putString("json", t.toString());
                return out;
            }
            case "list_add": {
                JSONObject t = Catalog.lookup(getContext(), arg);
                if (t != null) {
                    Catalog.listAdd(getContext(), t);
                    out.putString("json", t.toString());
                }
                return out;
            }
            case "list_remove":
                out.putInt("removed", Catalog.listRemove(getContext(), arg == null ? "" : arg));
                return out;
            default:
                return null;
        }
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
