package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A start-up title on black the first time Home appears after the TV boots or wakes from standby (config.json boot_intro_title and,
 * under it at a quarter of the size, boot_intro_subtitle). The title's letters draw together from wide
 * spacing as they fade in, the subtitle rises in after it, then the whole screen fades into Home. About
 * three seconds; any key skips it. The system's own boot animation is untouched (that needs root).
 */
final class BootIntro {
    private static final String PREFS = "boot_intro";

    private BootIntro() {
    }

    /** Whether the theme sets an intro at all. */
    static boolean enabled(Context ctx) {
        return !CardHome.string(ctx, "boot_intro_title").isEmpty();
    }

    /**
     * On wake from standby: the intro as a system overlay over whatever is on screen (an app keeps running under
     * it), so the TV is back where it was when the overlay fades. Needs "display over other apps"
     * (appops SYSTEM_ALERT_WINDOW, granted from the shell). Returns false when it cannot draw one.
     */
    static boolean showOverlay(Context ctx) {
        if (!enabled(ctx) || !Settings.canDrawOverlays(ctx)) return false;
        WindowManager wm = ctx.getSystemService(WindowManager.class);
        if (wm == null) return false;
        FrameLayout host = new FrameLayout(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        try {
            wm.addView(host, lp);
        } catch (RuntimeException e) {
            return false;
        }
        play(ctx, host, CardHome.string(ctx, "boot_intro_title"), CardHome.string(ctx, "boot_intro_subtitle"),
                false, () -> {
                    try {
                        wm.removeView(host);
                    } catch (RuntimeException ignored) {
                        // Already gone.
                    }
                });
        return true;
    }

    /** Shows the intro over root now (Home after the TV woke from standby). */
    static void showNow(Activity activity, FrameLayout root) {
        if (!enabled(activity)) return;
        show(activity, root, CardHome.string(activity, "boot_intro_title"), CardHome.string(activity, "boot_intro_subtitle"));
    }

    private static void show(Activity activity, FrameLayout root, String title, String subtitle) {
        play(activity, root, title, subtitle, true, null);
    }

    /** Shows the intro over root if this is the first Home since boot (or {@code force}). */
    static void maybeShow(Activity activity, FrameLayout root, boolean force) {
        String title = CardHome.string(activity, "boot_intro_title");
        if (title.isEmpty()) return;
        int boot = Settings.Global.getInt(activity.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!force && (boot < 0 || prefs.getInt("shown_boot", -2) == boot)) return;
        prefs.edit().putInt("shown_boot", boot).apply();
        show(activity, root, title, CardHome.string(activity, "boot_intro_subtitle"));
    }

    /**
     * Plays the intro in parent. skippable: the cover takes focus and any key ends it (inside Home; an overlay
     * cannot take keys without stealing them from the app under it). done runs once it has faded out.
     */
    private static void play(Context activity, FrameLayout root, String title, String subtitle, boolean skippable,
                             Runnable done) {
        float d = activity.getResources().getDisplayMetrics().density;
        FrameLayout cover = new FrameLayout(activity);
        cover.setBackgroundColor(Color.BLACK);
        if (skippable) {
            cover.setClickable(true);
            cover.setFocusable(true);
            cover.setFocusableInTouchMode(true);
        }

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        // Full width: the title is measured once, and its spacing then changes; a wrap_content width clipped its last letter.
        cover.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        float titleSp = 120f;
        TextView big = new TextView(activity);
        big.setText(title);
        big.setTextColor(Color.WHITE);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        big.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        big.setIncludeFontPadding(false);
        big.setAlpha(0f);
        big.setGravity(Gravity.CENTER_HORIZONTAL);
        big.setSingleLine(true);
        col.addView(big, new LinearLayout.LayoutParams(-1, -2));

        TextView small = new TextView(activity);
        small.setText(subtitle);
        small.setTextColor(Color.WHITE);
        small.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp / 4f);
        small.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        small.setLetterSpacing(0.08f);
        small.setAlpha(0f);
        small.setTranslationY(14 * d);
        small.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.topMargin = Math.round(18 * d);
        if (!subtitle.isEmpty()) col.addView(small, slp);

        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        if (skippable) cover.requestFocus();

        // Letters draw together from wide spacing while they fade in.
        ValueAnimator reveal = ValueAnimator.ofFloat(0f, 1f);
        reveal.setDuration(1300);
        reveal.setInterpolator(new DecelerateInterpolator(2f));
        reveal.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            big.setAlpha(t);
            big.setLetterSpacing(0.9f - 0.75f * t);
            big.setScaleX(1.06f - 0.06f * t);
            big.setScaleY(1.06f - 0.06f * t);
        });
        reveal.start();
        small.animate().alpha(0.9f).translationY(0f).setStartDelay(800).setDuration(700)
                .setInterpolator(new DecelerateInterpolator()).start();

        Runnable finish = () -> {
            if (cover.getParent() == null || cover.getTag() != null) return;
            cover.setTag("closing");
            reveal.cancel();
            cover.animate().alpha(0f).setDuration(600).withEndAction(() -> {
                root.removeView(cover);
                if (done != null) done.run();
            }).start();
        };
        cover.postDelayed(finish, 3000);
        // Any key skips; the key itself does nothing else.
        if (skippable) {
            cover.setOnKeyListener((v, keyCode, event) -> {
                finish.run();
                return true;
            });
        }
    }
}
