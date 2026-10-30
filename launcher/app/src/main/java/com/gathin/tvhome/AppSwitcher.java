package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Double-press Home: the apps used lately as large cards in a row, newest first, as Apple TV's app switcher.
 * Left/Right moves, OK opens (growing out of the card), Down closes that app (its background process), Back
 * closes the switcher. Built from Android's usage stats, the same source as the Continue card.
 */
final class AppSwitcher {
    private final Activity activity;
    private final FrameLayout root;
    private final float d;
    private FrameLayout overlay;
    private LinearLayout row;

    AppSwitcher(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        this.d = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() {
        return overlay != null;
    }

    void open() {
        if (overlay != null) return;
        List<String[]> apps = recentApps(8);   // {package, label, when}
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0xE6060810);
        overlay.setAlpha(0f);
        overlay.setClickable(true);

        TextView title = new TextView(activity);
        title.setText(apps.isEmpty() ? "No open apps" : "Open apps");
        title.setTextColor(0xB3FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(0.2f);
        title.setAllCaps(true);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.leftMargin = px(64);
        tlp.topMargin = px(56);
        overlay.addView(title, tlp);

        TextView keys = new TextView(activity);
        keys.setText("OK  Open      ↓  Close app      Back  Done");
        keys.setTextColor(0x80FFFFFF);
        keys.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        klp.bottomMargin = px(48);
        overlay.addView(keys, klp);

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(px(64), px(40), px(64), px(40));
        row = new LinearLayout(activity);
        row.setClipChildren(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row);
        overlay.addView(scroll, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));

        for (int i = 0; i < apps.size(); i++) row.addView(card(apps.get(i), i));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.animate().alpha(1f).setDuration(220).start();
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
        else overlay.requestFocus();
        overlay.setFocusable(row.getChildCount() == 0);
        overlay.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                close();
                return true;
            }
            return false;
        });
    }

    void close() {
        if (overlay == null) return;
        FrameLayout o = overlay;
        overlay = null;
        o.animate().alpha(0f).setDuration(180).withEndAction(() -> root.removeView(o)).start();
    }

    private View card(String[] app, int index) {
        String pkg = app[0];
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        int w = px(300), h = px(169);
        FrameLayout frame = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(16));
        bg.setColor(0xFF1A1D28);
        frame.setBackground(bg);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(16));
            }
        });
        frame.setClipToOutline(true);
        ImageView art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImageDrawable(artFor(pkg));
        frame.addView(art, new FrameLayout.LayoutParams(-1, -1));
        Sheen sheen = new Sheen();
        frame.setForeground(sheen);
        col.addView(frame, new LinearLayout.LayoutParams(w, h));
        TextView name = new TextView(activity);
        name.setText(app[1]);
        name.setTextColor(0xF2FFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView when = new TextView(activity);
        when.setText(DateUtils.getRelativeTimeSpanString(Long.parseLong(app[2]), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS));
        when.setTextColor(0x99FFFFFF);
        when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        when.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = px(14);
        col.addView(name, nlp);
        col.addView(when, new LinearLayout.LayoutParams(-1, -2));
        col.setAlpha(0.7f);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
        if (index > 0) lp.leftMargin = px(36);
        col.setLayoutParams(lp);
        col.setFocusable(true);
        col.setOnFocusChangeListener((v, focused) -> {
            sheen.setFocused(focused, true);
            if (focused) Sounds.tick();
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).alpha(focused ? 1f : 0.7f)
                    .translationZ(focused ? px(20) : 0f).setDuration(focused ? 320 : 200)
                    .setInterpolator(focused ? new OvershootInterpolator(0.9f) : new DecelerateInterpolator()).start();
        });
        col.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                Sounds.select();
                openApp(pkg, frame);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                closeApp(pkg, col);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                close();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_UP;
        });
        // Cards slide in from the right one after another.
        col.setTranslationX(px(80));
        col.animate().translationX(0f).setStartDelay(40L * index).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        return col;
    }

    private void openApp(String pkg, View from) {
        Intent i = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        LastOpened.record(activity, pkg);
        activity.startActivity(i, Zoom.from(from));
        close();
    }

    /** Like swiping a card up on Apple TV: the card drops away and the app's background process is ended. */
    private void closeApp(String pkg, View card) {
        ActivityManager am = activity.getSystemService(ActivityManager.class);
        if (am != null) am.killBackgroundProcesses(pkg);
        int index = row.indexOfChild(card);
        card.animate().translationY(px(220)).alpha(0f).setDuration(240).withEndAction(() -> {
            row.removeView(card);
            if (row.getChildCount() == 0) {
                close();
                return;
            }
            View next = row.getChildAt(Math.min(index, row.getChildCount() - 1));
            if (next.getLayoutParams() instanceof LinearLayout.LayoutParams && row.indexOfChild(next) == 0) {
                ((LinearLayout.LayoutParams) next.getLayoutParams()).leftMargin = 0;
                next.requestLayout();
            }
            next.requestFocus();
        }).start();
    }

    private Drawable artFor(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable art = launch == null ? null : pm.getActivityBanner(launch);
            if (art == null) art = pm.getApplicationBanner(pkg);
            return art != null ? art : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** TV apps used in the last day, newest first. */
    private List<String[]> recentApps(int max) {
        Map<String, Long> last = new HashMap<>();
        long now = System.currentTimeMillis();
        try {
            UsageStatsManager usm = activity.getSystemService(UsageStatsManager.class);
            List<UsageStats> stats = usm == null ? null
                    : usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 24L * 3600 * 1000, now);
            if (stats != null) for (UsageStats u : stats) {
                if (u.getLastTimeUsed() > 0 && u.getTotalTimeInForeground() > 0) {
                    last.merge(u.getPackageName(), u.getLastTimeUsed(), Long::max);
                }
            }
        } catch (RuntimeException ignored) {
            // No usage access: the apps Home opened instead.
        }
        if (last.isEmpty()) for (String pkg : LastOpened.recent(activity, max)) last.put(pkg, LastOpened.get(activity, pkg));
        PackageManager pm = activity.getPackageManager();
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : last.entrySet()) {
            String pkg = e.getKey();
            if (CardHome.NOT_RECENT.contains(pkg) || pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue;
            try {
                out.add(new String[]{pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(),
                        String.valueOf(e.getValue())});
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled since.
            }
        }
        out.sort((a, b) -> Long.compare(Long.parseLong(b[2]), Long.parseLong(a[2])));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }
}
