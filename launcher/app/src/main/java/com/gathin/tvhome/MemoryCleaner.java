package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Frees memory on this 1 GB TV: the TV apps sitting in the background (YouTube, Netflix, ...) are stopped, as
 * Android would under pressure, so the next app opens without waiting for memory. Android only stops processes that
 * are in the background, never the app on screen or one playing; a stopped app opens fresh next time. Runs weekly
 * once Home has been on screen for a while, or when Jarvis is asked ("clean up the TV").
 */
final class MemoryCleaner {
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String PREFS = "memory_cleaner";
    /** Never stopped: this setup's own parts, the Cast receiver and the voice search Jarvis listens with. */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "com.gathin.tvhome", "com.gathin.tvdiscover", "com.gathin.tvjarvis", "com.gathin.tvweather",
            "com.google.android.apps.mediashell", "com.google.android.katniss", "com.google.android.tts"));

    private MemoryCleaner() {
    }

    /** Due a weekly clean: a week since the last one (or never run). */
    static boolean due(Context ctx) {
        return System.currentTimeMillis() - prefs(ctx).getLong("last", 0) >= WEEK_MS;
    }

    /** Cleans on a background thread, then shows what it freed. {@code quiet}: no message when it freed little. */
    static void cleanAsync(Context ctx, boolean quiet) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            long freed = clean(app);
            int mb = (int) (freed / (1024 * 1024));
            if (quiet && mb < 50) return;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
                    mb > 0 ? app.getString(R.string.cleaned_up, mb) : app.getString(R.string.cleaned_up_none),
                    Toast.LENGTH_LONG).show());
        }, "memory-cleaner").start();
    }

    /** Stops the background TV apps; returns the memory freed in bytes (0 when nothing changed). */
    static long clean(Context ctx) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am == null) return 0;
        long before = available(am);
        PackageManager pm = ctx.getPackageManager();
        Intent tvApps = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent phoneApps = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> pkgs = new HashSet<>();
        for (Intent i : new Intent[]{tvApps, phoneApps}) {
            List<ResolveInfo> found = pm.queryIntentActivities(i, 0);
            for (ResolveInfo r : found) pkgs.add(r.activityInfo.packageName);
        }
        pkgs.removeAll(KEEP);
        for (String pkg : pkgs) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (RuntimeException ignored) {
                // Not allowed for this one: leave it.
            }
        }
        prefs(ctx).edit().putLong("last", System.currentTimeMillis()).apply();
        try {
            Thread.sleep(1500);   // the stopped processes' memory comes back over a moment
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Math.max(0, available(am) - before);
    }

    private static long available(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
