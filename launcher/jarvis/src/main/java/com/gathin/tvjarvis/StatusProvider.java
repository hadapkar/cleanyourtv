package com.gathin.tvjarvis;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Whether Jarvis can answer, for the launcher's Jarvis card: one row, ready = 1 when a Gemini key is set and
 * Gemini has not refused it. A refused key (revoked, expired, wrong) hides the card instead of leaving an
 * assistant on Home that can only show errors. No key, no request content and no answers are exposed.
 */
public class StatusProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"ready"});
        boolean ready = !Gemini.key(getContext()).isEmpty() && !Gemini.prefs(getContext()).getBoolean("refused", false);
        c.addRow(new Object[]{ready ? 1 : 0});
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
