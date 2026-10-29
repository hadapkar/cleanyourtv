package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Card home (config.json {@code "card_home": true}): frosted-glass cards over the wallpaper, above the dock.
 * A greeting, clock and weather card on the left, the apps opened last on the right, and a Jarvis card that
 * opens the TV's assistant. Everything is drawn in code from shapes and gradients, with no blur.
 *
 * The launcher stays offline: weather is read from the TV Weather add-on's content provider, which fetches it
 * itself. Without the add-on the weather line stays empty.
 */
final class CardHome {
    private static final Uri WEATHER = Uri.parse("content://com.gathin.tvweather/current");
    private static final long REFRESH_GAP_MS = 5 * 60 * 1000L;

    private final Activity activity;
    private final float d;
    private final FrameLayout container;
    private final TextView greeting;
    private final TextView weatherTemp;
    private final TextView weatherText;
    private final LinearLayout recentRow;
    private final TextView recentHint;
    private final OrbView orb;
    private long lastRefresh;

    /** Returns the cards when the theme turns them on, otherwise null. They sit in the root just under {@code above}. */
    static CardHome create(Activity activity, FrameLayout root, View above) {
        try {
            File file = new File(Config.dir(activity), Config.FILE_NAME);
            if (!file.isFile()) return null;
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!new JSONObject(json).optBoolean("card_home", false)) return null;
        } catch (Exception e) {
            return null;
        }
        return new CardHome(activity, root, above);
    }

    private CardHome(Activity activity, FrameLayout root, View above) {
        this.activity = activity;
        DisplayMetrics m = activity.getResources().getDisplayMetrics();
        d = m.density;
        int w = m.widthPixels, h = m.heightPixels;

        container = new FrameLayout(activity);
        container.setClipChildren(false);
        root.addView(container, root.indexOfChild(above), new FrameLayout.LayoutParams(-1, -1));

        int left = (int) (w * 0.059f), top = (int) (h * 0.20f), bottom = (int) (h * 0.685f);
        int gap = (int) (w * 0.011f), leftW = (int) (w * 0.36f);
        int rightX = left + leftW + gap, rightW = w - left - rightX;
        int rowGap = (int) (h * 0.02f), recentH = (int) ((bottom - top - rowGap) * 0.60f);

        // Left: greeting, clock, date, weather.
        LinearLayout leftCard = card(left, top, leftW, bottom - top);
        leftCard.setPadding(px(34), px(24), px(34), px(24));
        leftCard.setGravity(Gravity.CENTER_VERTICAL);
        greeting = text(13, 0xB3FFFFFF, "sans-serif-medium", 0.2f);
        greeting.setAllCaps(true);
        greeting.setSingleLine(true);
        leftCard.addView(greeting);
        LinearLayout timeRow = new LinearLayout(activity);
        timeRow.setGravity(Gravity.BOTTOM);
        TextClock clock = new TextClock(activity);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        style(clock, 78, 0xFFFFFFFF, "sans-serif-light", -0.03f);
        clock.setIncludeFontPadding(false);
        TextClock ampm = new TextClock(activity);
        ampm.setFormat12Hour("a");
        ampm.setFormat24Hour("");
        style(ampm, 17, 0xB3FFFFFF, "sans-serif-medium", 0.08f);
        ampm.setAllCaps(true);
        LinearLayout.LayoutParams ampmLp = new LinearLayout.LayoutParams(-2, -2);
        ampmLp.leftMargin = px(8);
        ampmLp.bottomMargin = px(12);
        timeRow.addView(clock);
        timeRow.addView(ampm, ampmLp);
        leftCard.addView(timeRow);
        TextClock date = new TextClock(activity);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        style(date, 17, 0xCCFFFFFF, "sans-serif", 0.02f);
        leftCard.addView(date);
        View divider = new View(activity);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        divLp.topMargin = px(18);
        divLp.bottomMargin = px(14);
        leftCard.addView(divider, divLp);
        weatherTemp = text(44, 0xFFFFFFFF, "sans-serif-light", -0.02f);
        weatherTemp.setIncludeFontPadding(false);
        weatherText = text(16, 0xCCFFFFFF, "sans-serif", 0.02f);
        weatherText.setSingleLine(true);
        leftCard.addView(weatherTemp);
        leftCard.addView(weatherText);

        // Right, top: the apps opened last.
        LinearLayout recent = card(rightX, top, rightW, recentH);
        recent.setPadding(px(30), px(22), px(30), px(18));
        TextView recentTitle = text(12, 0xB3FFFFFF, "sans-serif-medium", 0.2f);
        recentTitle.setAllCaps(true);
        recentTitle.setText("Recently opened");
        recent.addView(recentTitle);
        recentRow = new LinearLayout(activity);
        recentRow.setGravity(Gravity.CENTER_VERTICAL);
        recent.addView(recentRow, new LinearLayout.LayoutParams(-1, 0, 1f));
        recentHint = text(15, 0x99FFFFFF, "sans-serif", 0.02f);
        recentHint.setText("Apps you open from Home show up here");
        recentHint.setVisibility(View.GONE);
        recent.addView(recentHint);

        // Right, bottom: Jarvis.
        int jarvisY = top + recentH + rowGap;
        LinearLayout jarvis = card(rightX, jarvisY, rightW, bottom - jarvisY);
        jarvis.setOrientation(LinearLayout.HORIZONTAL);
        jarvis.setGravity(Gravity.CENTER_VERTICAL);
        jarvis.setPadding(px(34), px(14), px(34), px(14));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView jarvisKicker = text(12, 0xB3FFFFFF, "sans-serif-medium", 0.2f);
        jarvisKicker.setAllCaps(true);
        jarvisKicker.setText("Jarvis");
        TextView ask = text(32, 0xFFFFFFFF, "sans-serif-light", -0.01f);
        ask.setText("Just ask.");
        TextView hint = text(14, 0x99FFFFFF, "sans-serif", 0.02f);
        hint.setText("Press OK, then speak");
        words.addView(jarvisKicker);
        words.addView(ask);
        words.addView(hint);
        jarvis.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        orb = new OrbView(activity);
        jarvis.addView(orb, new LinearLayout.LayoutParams(px(66), px(66)));
        makeFocusable(jarvis, this::openAssistant);
    }

    /** The greeting line the launcher already works out (time of day, name, festival). */
    void setGreeting(CharSequence text) {
        greeting.setText(text);
    }

    /** Called on every return to Home: rebuilds the recent row and, at most every few minutes, the weather. */
    void refresh() {
        buildRecent();
        long now = System.currentTimeMillis();
        if (now - lastRefresh < REFRESH_GAP_MS) return;
        lastRefresh = now;
        new Thread(() -> {
            try {
                activity.getContentResolver().call(WEATHER, "refresh", null, null);
            } catch (RuntimeException ignored) {
                // No weather add-on installed.
            }
            readWeather();
            // The add-on fetches in the background; read again once a fresh value has had time to arrive.
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                return;
            }
            readWeather();
        }, "card-weather").start();
    }

    /** Re-reads the add-on's stored value only (no network); cheap enough for the same screen to call twice. */
    private void readWeather() {
        String temp = "";
        String text = "";
        try (Cursor c = activity.getContentResolver().query(WEATHER, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                temp = String.format(Locale.getDefault(), "%.0f°", c.getFloat(c.getColumnIndexOrThrow("temp_c")));
                text = c.getString(c.getColumnIndexOrThrow("text")) + " · " + c.getString(c.getColumnIndexOrThrow("city"));
            }
        } catch (RuntimeException ignored) {
            // No weather add-on, or it is not answering: leave the line empty.
        }
        final String t = temp, x = text;
        activity.runOnUiThread(() -> {
            weatherTemp.setText(t);
            weatherText.setText(x);
        });
    }

    private void buildRecent() {
        recentRow.removeAllViews();
        PackageManager pm = activity.getPackageManager();
        int shown = 0;
        for (String pkg : LastOpened.recent(activity, 12)) {
            if (shown == 4) break;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            Drawable art = null;
            CharSequence label = pkg;
            try {
                art = pm.getActivityBanner(launch);
                if (art == null) art = pm.getApplicationBanner(pkg);
                if (art == null) art = pm.getApplicationIcon(pkg);
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                continue;
            }
            recentRow.addView(recentItem(pkg, label, art, launch), shown == 0 ? first() : next());
            shown++;
        }
        recentHint.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        recentRow.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
    }

    private LinearLayout.LayoutParams first() {
        return new LinearLayout.LayoutParams(px(98), -2);
    }

    private LinearLayout.LayoutParams next() {
        LinearLayout.LayoutParams lp = first();
        lp.leftMargin = px(12);
        return lp;
    }

    private View recentItem(String pkg, CharSequence label, Drawable art, Intent launch) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(px(4), px(4), px(4), px(4));
        ImageView image = new ImageView(activity);
        image.setImageDrawable(art);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(8));
            }
        });
        image.setClipToOutline(true);
        item.addView(image, new LinearLayout.LayoutParams(-1, px(58)));
        TextView name = text(11, 0xCCFFFFFF, "sans-serif", 0.02f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setText(label);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = px(4);
        item.addView(name, nameLp);
        makeFocusable(item, () -> {
            LastOpened.record(activity, pkg);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(launch);
        });
        return item;
    }

    private void openAssistant() {
        for (String action : new String[]{"android.intent.action.VOICE_ASSIST", Intent.ACTION_ASSIST,
                "android.intent.action.VOICE_COMMAND"}) {
            try {
                activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // Try the next way to reach the assistant.
            }
        }
        Toast.makeText(activity, "No assistant found on this TV", Toast.LENGTH_SHORT).show();
    }

    /** Focusable with a soft lift and a brighter rim, so the remote can land on it like on a tile. */
    private void makeFocusable(View v, Runnable onOk) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> onOk.run());
        v.setOnFocusChangeListener((view, focused) ->
                view.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(140).start());
        v.setForeground(focusRing());
    }

    private Drawable focusRing() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(14));
        on.setStroke(px(2), 0xE6FFFFFF);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, new GradientDrawable());
        return s;
    }

    private LinearLayout card(int x, int y, int w, int h) {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x42FFFFFF, 0x16FFFFFF});
        fill.setCornerRadius(px(14));
        fill.setStroke(Math.max(1, px(1)), 0x4DFFFFFF);
        c.setBackground(fill);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        container.addView(c, lp);
        return c;
    }

    private TextView text(float sp, int color, String family, float spacing) {
        TextView t = new TextView(activity);
        style(t, sp, color, family, spacing);
        return t;
    }

    private static void style(TextView t, float sp, int color, String family, float spacing) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setLetterSpacing(spacing);
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }

    /** A soft glowing sphere that breathes slowly. */
    private static final class OrbView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ObjectAnimator pulse;

        OrbView(Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            pulse = ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, 1.08f);
            pulse.setDuration(2400);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.addUpdateListener(a -> setScaleY(getScaleX()));
            pulse.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (pulse != null) pulse.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            paint.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.2f,
                    new int[]{0xFFFFFFFF, 0xFFB9C8F5, 0xFF6D7FD6, 0x00334488}, new float[]{0f, 0.35f, 0.75f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
        }
    }
}
