package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A start-up title on black the first time Home appears after the TV boots (config.json boot_intro_title and,
 * under it at a quarter of the size, boot_intro_subtitle). The title's letters draw together from wide
 * spacing as they fade in, the subtitle rises in after it, then the whole screen fades into Home. About
 * three seconds; any key skips it. The system's own boot animation is untouched (that needs root).
 */
final class BootIntro {
    private static final String PREFS = "boot_intro";

    private BootIntro() {
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

    private static void show(Activity activity, FrameLayout root, String title, String subtitle) {
        float d = activity.getResources().getDisplayMetrics().density;
        FrameLayout cover = new FrameLayout(activity);
        cover.setBackgroundColor(Color.BLACK);
        cover.setClickable(true);
        cover.setFocusable(true);
        cover.setFocusableInTouchMode(true);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        cover.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        float titleSp = 120f;
        TextView big = new TextView(activity);
        big.setText(title);
        big.setTextColor(Color.WHITE);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        big.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        big.setIncludeFontPadding(false);
        big.setAlpha(0f);
        col.addView(big);

        TextView small = new TextView(activity);
        small.setText(subtitle);
        small.setTextColor(Color.WHITE);
        small.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp / 4f);
        small.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        small.setLetterSpacing(0.08f);
        small.setAlpha(0f);
        small.setTranslationY(14 * d);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.topMargin = Math.round(18 * d);
        if (!subtitle.isEmpty()) col.addView(small, slp);

        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        cover.requestFocus();

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
            cover.animate().alpha(0f).setDuration(600).withEndAction(() -> root.removeView(cover)).start();
        };
        cover.postDelayed(finish, 3000);
        // Any key skips; the key itself does nothing else.
        cover.setOnKeyListener((v, keyCode, event) -> {
            finish.run();
            return true;
        });
    }
}
