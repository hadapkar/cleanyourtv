package com.gathin.tvdiscover;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * Swipe through what is streaming: Right likes a title, Left passes, OK opens the app that streams it,
 * Back leaves. Each answer moves "For you" on the launcher towards the owner's genres.
 */
public class SwipeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ImageView poster;
    private ImageView backdrop;
    private TextView title;
    private TextView meta;
    private TextView overview;
    private TextView counter;
    private List<JSONObject> queue;
    private int index;
    private boolean animating;
    private float d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics m = getResources().getDisplayMetrics();
        d = m.density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF070B18);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.22f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00070B18, 0xCC070B18, 0xF2070B18}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        int posterH = (int) (m.heightPixels * 0.72f), posterW = posterH * 2 / 3;
        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * d);
            }
        });
        poster.setClipToOutline(true);
        poster.setElevation(12 * d);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(posterW, posterH, Gravity.CENTER_VERTICAL);
        plp.leftMargin = (int) (m.widthPixels * 0.12f);
        root.addView(poster, plp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams((int) (m.widthPixels * 0.42f), -2,
                Gravity.CENTER_VERTICAL);
        tlp.leftMargin = plp.leftMargin + posterW + (int) (48 * d);
        root.addView(text, tlp);
        counter = label(13, 0x99FFFFFF, "sans-serif-medium");
        counter.setLetterSpacing(0.2f);
        counter.setAllCaps(true);
        title = label(38, 0xFFFFFFFF, "sans-serif-light");
        meta = label(16, 0xCCFFFFFF, "sans-serif");
        overview = label(15, 0xB3FFFFFF, "sans-serif");
        overview.setMaxLines(5);
        overview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView keys = label(15, 0xE6FFFFFF, "sans-serif-medium");
        keys.setText("◀  Not for me      OK  Open      Like  ▶");
        text.addView(counter);
        text.addView(title, margins(8));
        text.addView(meta, margins(8));
        text.addView(overview, margins(18));
        text.addView(keys, margins(34));
        setContentView(root);

        queue = Catalog.queue(this);
        index = 0;
        show();
        if (queue.isEmpty()) {
            // Nothing fetched yet (or everything rated): try once, then show what there is.
            new Thread(() -> {
                Catalog.refresh(this);
                handler.post(() -> {
                    queue = Catalog.queue(this);
                    index = 0;
                    show();
                });
            }, "swipe-refresh").start();
        }
    }

    private TextView label(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (top * d);
        return lp;
    }

    private void show() {
        poster.setTranslationX(0f);
        poster.setRotation(0f);
        poster.setAlpha(1f);
        poster.setImageDrawable(null);
        backdrop.setImageDrawable(null);
        if (index >= queue.size()) {
            counter.setText("All caught up");
            title.setText(Catalog.token(this).isEmpty() ? "TMDB is not set up yet" : "Nothing new to rate");
            meta.setText("");
            overview.setText(Catalog.token(this).isEmpty()
                    ? "Add the TMDB token from the laptop, then come back."
                    : "New titles arrive every few hours. Your picks are on Home under For you.");
            return;
        }
        JSONObject t = queue.get(index);
        counter.setText((queue.size() - index) + " to rate · on " + t.optString("service"));
        title.setText(t.optString("title"));
        String year = t.optString("year");
        String rating = t.optDouble("rating", 0) > 0 ? String.format(java.util.Locale.ROOT, "★ %.1f", t.optDouble("rating")) : "";
        meta.setText(join(year, t.optString("genre_names"), rating));
        overview.setText(t.optString("overview"));
        final int shown = index;
        new Thread(() -> {
            File f = Catalog.poster(this, t);
            Bitmap b = f == null ? null : BitmapFactory.decodeFile(f.getPath());
            handler.post(() -> {
                if (shown != index || b == null) return;
                poster.setImageBitmap(b);
                backdrop.setImageBitmap(b);
            });
            // Fetch the next poster ahead so the following swipe shows at once.
            if (shown + 1 < queue.size()) Catalog.poster(this, queue.get(shown + 1));
        }, "swipe-poster").start();
    }

    private static String join(String... parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (s.length() > 0) s.append("   ·   ");
            s.append(p);
        }
        return s.toString();
    }

    private void answer(boolean like) {
        if (animating || index >= queue.size()) return;
        animating = true;
        Catalog.rate(this, queue.get(index), like);
        float dx = (like ? 1 : -1) * getResources().getDisplayMetrics().widthPixels * 0.6f;
        poster.animate().translationX(dx).rotation(like ? 14f : -14f).alpha(0f).setDuration(260)
                .withEndAction(() -> {
                    index++;
                    animating = false;
                    show();
                }).start();
    }

    private void open() {
        if (index >= queue.size()) return;
        String pkg = queue.get(index).optString("pkg");
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                answer(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                answer(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                open();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
