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
 * A greeting, clock and weather card on the left; on the right "For you" posters and a Swipe card above the
 * apps opened last and a Jarvis card that opens the TV's assistant. Everything is drawn in code from shapes and
 * gradients, with no blur.
 *
 * The launcher stays offline: weather comes from the TV Weather add-on and titles and posters from the TV
 * Discover add-on, each through its content provider; the add-ons do their own fetching. Without them those
 * cards say how to set them up.
 */
final class CardHome {
    private static final Uri WEATHER = Uri.parse("content://com.gathin.tvweather/current");
    private static final Uri FOR_YOU = Uri.parse("content://com.gathin.tvdiscover/for_you");
    private static final Uri QUEUE = Uri.parse("content://com.gathin.tvdiscover/queue");
    private static final Uri CONTINUE = Uri.parse("content://com.gathin.tvdiscover/continue");
    private static final String DISCOVER_PKG = "com.gathin.tvdiscover";
    /** The frosted backdrop is the screen behind the cards at 1/FROST size, blurred. */
    private static final int FROST = 8;
    private static final long REFRESH_GAP_MS = 5 * 60 * 1000L;

    private final Activity activity;
    private final boolean tvos;
    private final float d;
    private final FrameLayout container;
    private final TextView greeting;
    private final TextView weatherTemp;
    private final TextView weatherText;
    private final LinearLayout recentRow;
    private final TextView recentHint;
    private final OrbView orb;
    private final LinearLayout forYouRow;
    private final TextView forYouHint;
    private final TextView swipeCount;
    private final ImageView[] swipeStack = new ImageView[2];
    private int posterW, posterH, posterSlots;
    /** The bottom-left card's items: three always fit its width, 16:9 artwork under its title. */
    private int recentItemW, recentImgH;
    /** How many items the bottom-left card shows: as many as fit at a readable size, at most four. */
    private int recentSlots = 3;
    private TextView recentTitle;
    /** Blurred copy of what is behind the cards; drawn by every card's Frost background. Main thread only. */
    private android.graphics.Bitmap frost;
    private final Runnable frostTask = this::captureFrost;
    private long lastRefresh;

    /** Returns the cards when the theme turns them on, otherwise null. They sit in the root just under {@code above}. */
    static CardHome create(Activity activity, FrameLayout root, View above) {
        return enabled(activity) ? new CardHome(activity, root, above) : null;
    }

    /** Whether the pushed theme's config.json turns card home on. */
    static boolean enabled(Context ctx) {
        return flag(ctx, "card_home");
    }

    /** A string from the pushed theme's config.json that Config does not know (boot_intro_title, ...), or "". */
    static String string(Context ctx, String key) {
        try {
            File file = new File(Config.dir(ctx), Config.FILE_NAME);
            if (!file.isFile()) return "";
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(json).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** A boolean from the pushed theme's config.json that Config does not know (card_home, tvos_focus). */
    static boolean flag(Context ctx, String key) {
        try {
            File file = new File(Config.dir(ctx), Config.FILE_NAME);
            if (!file.isFile()) return false;
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(json).optBoolean(key, false);
        } catch (Exception e) {
            return false;
        }
    }

    private CardHome(Activity activity, FrameLayout root, View above) {
        this.activity = activity;
        tvos = flag(activity, "tvos_focus");
        DisplayMetrics m = activity.getResources().getDisplayMetrics();
        d = m.density;
        int w = m.widthPixels, h = m.heightPixels;

        container = new FrameLayout(activity);
        container.setClipChildren(false);
        root.addView(container, root.indexOfChild(above), new FrameLayout.LayoutParams(-1, -1));

        int left = (int) (w * 0.059f), top = (int) (h * 0.20f), bottom = (int) (h * 0.685f);
        int gap = (int) (w * 0.011f), leftW = (int) (w * 0.36f);
        int rightX = left + leftW + gap, rightW = w - left - rightX;
        int rowGap = (int) (h * 0.02f);

        // Left: greeting, clock, date, weather.
        LinearLayout leftCard = card(left, top, leftW, bottom - top);
        leftCard.setPadding(px(34), px(18), px(34), px(18));
        leftCard.setGravity(Gravity.CENTER_VERTICAL);
        greeting = text(15, 0xD9FFFFFF, "sans-serif-medium", 0.03f);
        greeting.setSingleLine(true);
        leftCard.addView(greeting);
        LinearLayout timeRow = new LinearLayout(activity);
        timeRow.setGravity(Gravity.BOTTOM);
        TextClock clock = new TextClock(activity);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        style(clock, 70, 0xFFFFFFFF, "sans-serif-light", -0.03f);
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
        divLp.topMargin = px(14);
        divLp.bottomMargin = px(10);
        leftCard.addView(divider, divLp);
        // The temperature with the condition and place beside it, so the card never runs out of height.
        LinearLayout weatherRow = new LinearLayout(activity);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherTemp = text(40, 0xFFFFFFFF, "sans-serif-light", -0.02f);
        weatherTemp.setIncludeFontPadding(false);
        weatherText = text(16, 0xCCFFFFFF, "sans-serif", 0.02f);
        weatherText.setSingleLine(true);
        weatherText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams wtLp = new LinearLayout.LayoutParams(0, -2, 1f);
        wtLp.leftMargin = px(14);
        weatherRow.addView(weatherTemp);
        weatherRow.addView(weatherText, wtLp);
        leftCard.addView(weatherRow);

        // Right: two rows, For you and Swipe over Recently opened and Jarvis.
        int topRowH = (int) ((bottom - top - rowGap) * 0.56f);
        int forYouW = (int) ((rightW - gap) * 0.70f), swipeW = rightW - gap - forYouW;
        int bottomY = top + topRowH + rowGap, bottomH = bottom - bottomY;
        int recentW = forYouW, jarvisW = swipeW;

        LinearLayout forYou = card(rightX, top, forYouW, topRowH);
        forYou.setPadding(px(24), px(16), px(24), px(14));
        forYou.addView(kicker("For you"));
        forYouRow = new LinearLayout(activity);
        forYouRow.setGravity(Gravity.CENTER_VERTICAL);
        forYou.addView(forYouRow, new LinearLayout.LayoutParams(-1, 0, 1f));
        forYouHint = text(15, 0x99FFFFFF, "sans-serif", 0.02f);
        forYouHint.setVisibility(View.GONE);
        forYou.addView(forYouHint);
        posterH = topRowH - px(16 + 14) - px(24);
        posterW = posterH * 2 / 3;
        int innerW = forYouW - px(48);
        posterSlots = Math.max(1, Math.min(5, (innerW + px(10)) / (posterW + px(10))));

        LinearLayout swipe = card(rightX + forYouW + gap, top, swipeW, topRowH);
        swipe.setPadding(px(20), px(16), px(20), px(14));
        swipe.addView(kicker("Swipe"));
        FrameLayout stack = new FrameLayout(activity);
        stack.setClipChildren(false);
        int thumbH = topRowH - px(16 + 14) - px(24 + 40), thumbW = thumbH * 2 / 3;
        for (int i = 1; i >= 0; i--) {
            ImageView v = posterView();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(thumbW, thumbH, Gravity.CENTER);
            v.setRotation(i == 1 ? 8f : -4f);
            v.setTranslationX(i == 1 ? px(14) : -px(6));
            stack.addView(v, lp);
            swipeStack[i] = v;
        }
        swipe.addView(stack, new LinearLayout.LayoutParams(-1, 0, 1f));
        swipeCount = text(13, 0xCCFFFFFF, "sans-serif", 0.02f);
        swipeCount.setGravity(Gravity.CENTER_HORIZONTAL);
        swipe.addView(swipeCount, new LinearLayout.LayoutParams(-1, -2));
        makeFocusable(swipe, this::openSwipe);

        LinearLayout recent = card(rightX, bottomY, recentW, bottomH);
        recent.setPadding(px(24), px(14), px(24), px(10));
        int recentInner = recentW - px(48);
        recentSlots = recentInner >= px(4 * 96 + 3 * 10) ? 4 : 3;
        recentItemW = (recentInner - (recentSlots - 1) * px(10)) / recentSlots;
        // Height left under the title for artwork and a one-line name.
        int room = bottomH - px(14 + 10) - px(24) - px(22);
        recentImgH = Math.min(recentItemW * 9 / 16, room);
        recentTitle = kicker("Recently opened");
        recent.addView(recentTitle);
        recentRow = new LinearLayout(activity);
        recentRow.setGravity(Gravity.CENTER_VERTICAL);
        recent.addView(recentRow, new LinearLayout.LayoutParams(-1, 0, 1f));
        recentHint = text(14, 0x99FFFFFF, "sans-serif", 0.02f);
        recentHint.setText("Apps you open from Home show up here");
        recentHint.setVisibility(View.GONE);
        recent.addView(recentHint);

        // Jarvis.
        LinearLayout jarvis = card(rightX + recentW + gap, bottomY, jarvisW, bottomH);
        // Laid out like the Swipe card above it: title, the orb in the middle, one short line under it.
        jarvis.setPadding(px(20), px(14), px(20), px(12));
        jarvis.addView(kicker("Jarvis"));
        FrameLayout orbBox = new FrameLayout(activity);
        orb = new OrbView(activity);
        int orbSize = Math.min(px(64), bottomH - px(14 + 12) - px(24 + 26));
        orbBox.addView(orb, new FrameLayout.LayoutParams(orbSize, orbSize, Gravity.CENTER));
        jarvis.addView(orbBox, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = text(13, 0xCCFFFFFF, "sans-serif", 0.02f);
        hint.setText("Just ask · OK");
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        jarvis.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        makeFocusable(jarvis, this::openAssistant);
    }

    /** The greeting line the launcher already works out (time of day, name, festival). */
    void setGreeting(CharSequence text) {
        greeting.setText(text);
    }

    /** Called on every return to Home: rebuilds the recent row and, at most every few minutes, the weather. */
    void refresh() {
        buildRecent();
        container.removeCallbacks(frostTask);
        container.postDelayed(frostTask, 1200);
        container.postDelayed(frostTask, 6000);
        new Thread(this::readDiscover, "card-discover").start();
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
            if (shown == recentSlots) break;
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
        return new LinearLayout.LayoutParams(recentItemW, -2);
    }

    private LinearLayout.LayoutParams next() {
        LinearLayout.LayoutParams lp = first();
        lp.leftMargin = px(10);
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
        item.addView(image, new LinearLayout.LayoutParams(-1, recentImgH));
        TextView name = text(12, 0xCCFFFFFF, "sans-serif", 0.02f);
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
            opening(pkg);
        });
        return item;
    }

    /** For you posters and the Swipe card's stack and count, from the TV Discover add-on. Off the main thread. */
    private void readDiscover() {
        boolean installed = installed(DISCOVER_PKG);
        java.util.List<String[]> picks = new java.util.ArrayList<>();   // {title, pkg, poster uri}
        java.util.List<String> queuePosters = new java.util.ArrayList<>();
        int queued = 0;
        java.util.List<String[]> resume = new java.util.ArrayList<>();   // {title, pkg, intent, progress, poster}
        if (installed) {
            try (Cursor c = activity.getContentResolver().query(CONTINUE, null, null, null, null)) {
                while (c != null && c.moveToNext() && resume.size() < 4) {
                    resume.add(new String[]{c.getString(c.getColumnIndexOrThrow("title")),
                            c.getString(c.getColumnIndexOrThrow("pkg")), c.getString(c.getColumnIndexOrThrow("intent")),
                            String.valueOf(c.getInt(c.getColumnIndexOrThrow("progress"))),
                            c.getString(c.getColumnIndexOrThrow("poster"))});
                }
            } catch (RuntimeException ignored) {
                // No Continue watching yet (permission not granted, or nothing published).
            }
            try (Cursor c = activity.getContentResolver().query(FOR_YOU, null, null, null, null)) {
                while (c != null && c.moveToNext() && picks.size() < posterSlots) {
                    picks.add(new String[]{c.getString(c.getColumnIndexOrThrow("title")),
                            c.getString(c.getColumnIndexOrThrow("pkg")), c.getString(c.getColumnIndexOrThrow("poster"))});
                }
            } catch (RuntimeException ignored) {
                // The add-on is updating or not answering: show the hint for now.
            }
            try (Cursor c = activity.getContentResolver().query(QUEUE, null, null, null, null)) {
                if (c != null) {
                    queued = c.getCount();
                    while (c.moveToNext() && queuePosters.size() < 2) {
                        queuePosters.add(c.getString(c.getColumnIndexOrThrow("poster")));
                    }
                }
            } catch (RuntimeException ignored) {
                // As above.
            }
        }
        android.graphics.Bitmap[] art = new android.graphics.Bitmap[picks.size()];
        for (int i = 0; i < art.length; i++) art[i] = decodePoster(picks.get(i)[2]);
        android.graphics.Bitmap[] stackArt = new android.graphics.Bitmap[queuePosters.size()];
        for (int i = 0; i < stackArt.length; i++) stackArt[i] = decodePoster(queuePosters.get(i));
        android.graphics.Bitmap[] resumeArt = new android.graphics.Bitmap[resume.size()];
        for (int i = 0; i < resumeArt.length; i++) resumeArt[i] = decodePoster(resume.get(i)[4]);
        final int count = queued;
        activity.runOnUiThread(() -> {
            showDiscover(installed, picks, art, stackArt, count);
            showContinue(resume, resumeArt);
        });
    }

    private void showDiscover(boolean installed, java.util.List<String[]> picks, android.graphics.Bitmap[] art,
                              android.graphics.Bitmap[] stackArt, int queued) {
        forYouRow.removeAllViews();
        int shown = 0;
        for (int i = 0; i < picks.size(); i++) {
            if (art[i] == null) continue;
            String[] p = picks.get(i);
            ImageView v = posterView();
            v.setImageBitmap(art[i]);
            v.setContentDescription(p[0]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(posterW, posterH);
            if (shown > 0) lp.leftMargin = px(10);
            makeFocusable(v, () -> launch(p[1]));
            forYouRow.addView(v, lp);
            shown++;
        }
        forYouHint.setText(!installed ? "Install TV Discover to see picks here"
                : "Swipe a few titles to get picks for you");
        forYouHint.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        forYouRow.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < swipeStack.length; i++) {
            swipeStack[i].setImageBitmap(i < stackArt.length ? stackArt[i] : null);
            swipeStack[i].setVisibility(i < stackArt.length && stackArt[i] != null ? View.VISIBLE : View.INVISIBLE);
        }
        swipeCount.setText(!installed ? "Not installed" : queued > 0 ? queued + " to rate" : "OK to start");
    }

    /** Continue watching takes the bottom-left card when there is any; otherwise it keeps Recently opened. */
    private void showContinue(java.util.List<String[]> rows, android.graphics.Bitmap[] art) {
        if (rows.isEmpty()) {
            recentTitle.setText("Recently opened");
            return;
        }
        recentTitle.setText("Continue watching");
        recentRow.removeAllViews();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(px(4), px(4), px(4), px(4));
            FrameLayout frame = new FrameLayout(activity);
            ImageView image = posterView();
            if (art[i] != null) image.setImageBitmap(art[i]);
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            int progress = Math.max(0, Math.min(100, Integer.parseInt(r[3])));
            if (progress > 0) {
                View bar = new View(activity);
                bar.setBackgroundColor(0xFFE50914);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams((recentItemW - px(8)) * progress / 100, px(3),
                        Gravity.BOTTOM | Gravity.START);
                frame.addView(bar, blp);
            }
            item.addView(frame, new LinearLayout.LayoutParams(-1, recentImgH));
            TextView name = text(12, 0xCCFFFFFF, "sans-serif", 0.02f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setText(r[0]);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
            nameLp.topMargin = px(4);
            item.addView(name, nameLp);
            makeFocusable(item, () -> openIntent(r[2], r[1]));
            if (i == recentSlots) break;
            recentRow.addView(item, i == 0 ? first() : next());
        }
        recentHint.setVisibility(View.GONE);
        recentRow.setVisibility(View.VISIBLE);
    }

    /** A Watch Next row's own intent, only if it really opens the app that published it. */
    private void openIntent(String uri, String pkg) {
        try {
            Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            i.setSelector(null);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ResolveInfo r = activity.getPackageManager().resolveActivity(i, 0);
            if (r == null || !pkg.equals(r.activityInfo.packageName)) {
                launch(pkg);
                return;
            }
            LastOpened.record(activity, pkg);
            activity.startActivity(i);
            opening(pkg);
        } catch (Exception e) {
            launch(pkg);
        }
    }

    private void opening(String pkg) {
        if (!(activity instanceof MainActivity)) return;
        CharSequence label = pkg;
        try {
            PackageManager pm = activity.getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep the package name.
        }
        ((MainActivity) activity).showOpening(label);
    }

    /** Draws the screen behind the cards (cards hidden) at 1/FROST size and blurs it, for the Frost backgrounds. */
    private void captureFrost() {
        View root = (View) container.getParent();
        int w = root.getWidth() / FROST, h = root.getHeight() / FROST;
        if (w <= 0 || h <= 0) return;
        android.graphics.Bitmap small;
        try {
            small = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return;
        }
        android.graphics.Canvas canvas = new android.graphics.Canvas(small);
        canvas.scale(1f / FROST, 1f / FROST);
        int was = container.getVisibility();
        container.setVisibility(View.INVISIBLE);
        try {
            root.draw(canvas);
        } catch (RuntimeException e) {
            return;
        } finally {
            container.setVisibility(was);
        }
        blur(small, 3);
        // The old bitmap is left to the garbage collector: a frame being drawn may still hold it.
        frost = small;
        for (int i = 0; i < container.getChildCount(); i++) container.getChildAt(i).invalidate();
    }

    /** Three box-blur passes each way: close to a Gaussian, and cheap at 1/8 screen size. */
    private static void blur(android.graphics.Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int sr = 0, sg = 0, sb = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.min(len - 1, Math.max(0, i + k));
                    int c = horizontal ? in[line * w + j] : in[j * w + line];
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                }
                int n = 2 * r + 1;
                int v = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                if (horizontal) out[line * w + i] = v;
                else out[i * w + line] = v;
            }
        }
    }

    /** A card's background: the blurred screen behind it, clipped to the card's corners, under its tint and rim. */
    private final class Frost extends Drawable {
        private final int x, y;
        private final GradientDrawable glass;
        private final android.graphics.Path clip = new android.graphics.Path();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Rect src = new android.graphics.Rect();

        Frost(int x, int y, GradientDrawable glass) {
            this.x = x;
            this.y = y;
            this.glass = glass;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            glass.setBounds(b);
            clip.reset();
            clip.addRoundRect(new android.graphics.RectF(b), px(14), px(14), android.graphics.Path.Direction.CW);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Bitmap f = frost;
            if (f != null) {
                android.graphics.Rect b = getBounds();
                src.set(x / FROST, y / FROST, (x + b.width()) / FROST, (y + b.height()) / FROST);
                canvas.save();
                canvas.clipPath(clip);
                canvas.drawBitmap(f, src, b, paint);
                canvas.restore();
            }
            glass.draw(canvas);
        }

        @Override public void setAlpha(int alpha) { glass.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { glass.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private android.graphics.Bitmap decodePoster(String uri) {
        if (uri == null) return null;
        try (android.os.ParcelFileDescriptor fd = activity.getContentResolver().openFileDescriptor(Uri.parse(uri), "r")) {
            if (fd == null) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;   // w342 posters shown about 110 px wide
            o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private boolean installed(String pkg) {
        try {
            activity.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void launch(String pkg) {
        PackageManager pm = activity.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        LastOpened.record(activity, pkg);
        activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        opening(pkg);
    }

    private void openSwipe() {
        try {
            activity.startActivity(new Intent().setClassName(DISCOVER_PKG, DISCOVER_PKG + ".SwipeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(activity, "TV Discover is not installed", Toast.LENGTH_SHORT).show();
        }
    }

    private ImageView posterView() {
        ImageView v = new ImageView(activity);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(8));
            }
        });
        v.setClipToOutline(true);
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setCornerRadius(px(8));
        placeholder.setColor(0x22FFFFFF);
        v.setBackground(placeholder);
        return v;
    }

    private TextView kicker(String label) {
        TextView t = text(12, 0xB3FFFFFF, "sans-serif-medium", 0.2f);
        t.setAllCaps(true);
        t.setText(label);
        return t;
    }

    private void openAssistant() {
        if (installed("com.gathin.tvjarvis")) {
            try {
                activity.startActivity(new Intent().setClassName("com.gathin.tvjarvis", "com.gathin.tvjarvis.JarvisActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // Fall through to the system assistant.
            }
        }
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

    /**
     * Focusable so the remote can land on it like on a tile. With tvos_focus it lifts with a shadow and a sheen,
     * as Apple TV does; otherwise it grows a little and shows a bright rim.
     */
    private void makeFocusable(View v, Runnable onOk) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> {
            // An app opened from Home is still starting: a second press would open another one.
            if (activity instanceof MainActivity && ((MainActivity) activity).launching()) return;
            onOk.run();
        });
        if (tvos) {
            Sheen sheen = new Sheen();
            v.setForeground(sheen);
            v.setClipToOutline(true);
            v.setOnFocusChangeListener((view, focused) -> {
                sheen.setFocused(focused, true);
                view.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                        .translationZ(focused ? px(18) : 0f).setDuration(focused ? 240 : 180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            });
            return;
        }
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
                new int[]{0x3DFFFFFF, 0x12FFFFFF});
        fill.setCornerRadius(px(14));
        fill.setStroke(Math.max(1, px(1)), 0x59FFFFFF);
        c.setBackground(new Frost(x, y, fill));
        c.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(14));
            }
        });
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
