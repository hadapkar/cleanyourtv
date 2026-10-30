package com.gathin.tvjarvis;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Remind me at 8 for the match": kept in private storage, set as an exact alarm, re-armed after a reboot, and
 * shown at its time as a glass banner over whatever is on screen (display over other apps, granted from the
 * shell; a toast without it). The banner takes no focus, so the app under it keeps the remote.
 */
public class Reminders extends BroadcastReceiver {
    private static final String PREFS = "reminders";

    static void add(Context ctx, long when, String text) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int id = p.getInt("next_id", 1);
        JSONArray all = load(ctx);
        try {
            all.put(new JSONObject().put("id", id).put("when", when).put("text", text));
        } catch (org.json.JSONException ignored) {
            return;
        }
        p.edit().putInt("next_id", id + 1).putString("all", all.toString()).apply();
        arm(ctx, id, when, text);
    }

    private static JSONArray load(Context ctx) {
        try {
            return new JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("all", "[]"));
        } catch (org.json.JSONException e) {
            return new JSONArray();
        }
    }

    private static void arm(Context ctx, int id, long when, String text) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent i = new Intent(ctx, Reminders.class).putExtra("id", id).putExtra("text", text);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context ctx = context.getApplicationContext();
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Alarms do not survive a reboot: set the future ones again, drop the past ones.
            JSONArray all = load(ctx), keep = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < all.length(); i++) {
                JSONObject r = all.optJSONObject(i);
                if (r == null || r.optLong("when") <= now) continue;
                keep.put(r);
                arm(ctx, r.optInt("id"), r.optLong("when"), r.optString("text"));
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("all", keep.toString()).apply();
            return;
        }
        int id = intent.getIntExtra("id", -1);
        String text = intent.getStringExtra("text");
        JSONArray all = load(ctx), keep = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r != null && r.optInt("id") != id) keep.put(r);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("all", keep.toString()).apply();
        PendingResult pending = goAsync();
        show(ctx, text == null ? "Reminder" : text, pending);
    }

    private static void show(Context ctx, String text, PendingResult pending) {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "⏰  " + text, Toast.LENGTH_LONG).show();
            pending.finish();
            return;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * d);
        box.setPadding(pad, Math.round(16 * d), pad, Math.round(16 * d));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE6252A3A, 0xE6151824});
        bg.setCornerRadius(18 * d);
        bg.setStroke(Math.max(1, Math.round(d)), 0x66FFFFFF);
        box.setBackground(bg);
        TextView kicker = new TextView(ctx);
        kicker.setText("JARVIS  ·  REMINDER");
        kicker.setTextColor(0xB3FFFFFF);
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        kicker.setLetterSpacing(0.2f);
        TextView body = new TextView(ctx);
        body.setText(text);
        body.setTextColor(0xFFFFFFFF);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        body.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        box.addView(kicker);
        box.addView(body);
        WindowManager wm = ctx.getSystemService(WindowManager.class);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(48 * d);
        lp.y = Math.round(48 * d);
        try {
            wm.addView(box, lp);
        } catch (RuntimeException e) {
            pending.finish();
            return;
        }
        box.setAlpha(0f);
        box.setTranslationX(40 * d);
        box.animate().alpha(1f).translationX(0f).setDuration(300).start();
        new Handler(Looper.getMainLooper()).postDelayed(() -> box.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            try {
                wm.removeView(box);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            pending.finish();
        }).start(), 15_000);
    }
}
