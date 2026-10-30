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
    /** plain_glass in the theme: cards without the frosted (blurred) backdrop, see captureFrost. */
    private final boolean plainGlass;
    /** The card or item being pressed, for Zoom. Main thread only. */
    private View pressed;
    private final float d;
    private final FrameLayout container;
    private final TextView greeting;
    private final TextView weatherTemp;
    private final TextView weatherText;
    private final LinearLayout recentRow;
    private final TextView recentHint;
    private final OrbView orb;
    /** The cards and the area they share, kept so relayout() can move them when a service drops out. */
    private LinearLayout leftCardView, forYouCard, swipeCard, recentCard, jarvisCard;
    private View weatherDivider, weatherLine;
    private int gTop, gBottom, gRightX, gRightW, gGap, gRowGap;
    private String layoutKey = "";
    private static final Uri JARVIS_STATUS = Uri.parse("content://com.gathin.tvjarvis.status/ready");
    /** Weather older than this is not shown (the add-on cannot reach Open-Meteo). */
    private static final long WEATHER_MAX_AGE_MS = 6 * 60 * 60 * 1000L;
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

    /** Screen x of the centre of the last card or poster that took focus, for the direction of the next tilt. */
    private float lastFocusX = Float.NaN;
    private final java.util.Map<View, ValueAnimator> drift = new java.util.WeakHashMap<>();

    /**
     * Apple TV's parallax on a poster: it tilts in from the side focus came from and springs level, then drifts
     * slowly in 3D while it keeps focus. Rotations only (no layer per frame), so it stays smooth on 1 GB.
     */
    private void parallax(View v, boolean focused) {
        ValueAnimator old = drift.remove(v);
        if (old != null) old.cancel();
        v.setCameraDistance(9000 * d);
        if (!focused) {
            v.animate().rotationX(0f).rotationY(0f).setDuration(200).start();
            return;
        }
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        float cx = at[0] + v.getWidth() / 2f;
        float dir = Float.isNaN(lastFocusX) ? 0f : Math.signum(cx - lastFocusX);
        lastFocusX = cx;
        v.setRotationY(-dir * 12f);
        v.animate().rotationY(0f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .withEndAction(() -> {
                    if (!v.hasFocus() && !v.isFocused()) return;
                    ValueAnimator a = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
                    a.setDuration(3600);
                    // One slow sway and rest: an endless drift kept the screen redrawing (stutter on 1 GB).
                    a.setRepeatCount(0);
                    a.setInterpolator(new android.view.animation.LinearInterpolator());
                    a.addUpdateListener(an -> {
                        float t = (float) an.getAnimatedValue();
                        v.setRotationY(2.5f * (float) Math.sin(t));
                        v.setRotationX(1.5f * (float) Math.cos(t));
                    });
                    drift.put(v, a);
                    a.start();
                }).start();
    }

    private android.os.Bundle zoom() {
        return tvos ? Zoom.from(pressed) : null;
    }

    /**
     * Back on Home from an app: the cards and the rows settle in from slightly larger, fading in, as Apple TV's
     * Home does when an app closes. {@code main} is the header and app rows.
     */
    void settleIn(View main) {
        if (!tvos) return;
        for (View v : new View[]{container, main}) {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setScaleX(1.04f);
            v.setScaleY(1.04f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(320).setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
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
        plainGlass = flag(activity, "plain_glass");
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
        leftCardView = leftCard;
        makeFocusable(leftCard, this::openPersonalize);
        leftCard.setOnLongClickListener(v -> {
            openPersonalize();
            return true;
        });
        forYouCard = forYou;
        swipeCard = swipe;
        recentCard = recent;
        jarvisCard = jarvis;
        weatherDivider = divider;
        weatherLine = weatherRow;
        gTop = top;
        gBottom = bottom;
        gRightX = rightX;
        gRightW = rightW;
        gGap = gap;
        gRowGap = rowGap;
        // Until the first read says otherwise, the service-backed parts stay hidden: Home never flashes empty cards.
        weatherDivider.setVisibility(View.GONE);
        weatherLine.setVisibility(View.GONE);
        relayout(false, false, false);
    }

    /**
     * Places the right-hand cards for what can be shown: For you and Swipe (TV Discover has titles), Continue
     * (anything to continue) and Jarvis (a working Gemini key). A missing part leaves no gap: rows and cards
     * grow into its place, and with none of them the clock card stands alone.
     */
    private void relayout(boolean discover, boolean cont, boolean jarvisOk) {
        String key = discover + "," + cont + "," + jarvisOk;
        if (key.equals(layoutKey)) return;
        layoutKey = key;
        boolean topRow = discover, bottomRow = cont || jarvisOk;
        int rows = (topRow ? 1 : 0) + (bottomRow ? 1 : 0);
        int full = gBottom - gTop;
        int topH = rows == 2 ? (int) ((full - gRowGap) * 0.56f) : full;
        int wideW = (int) ((gRightW - gGap) * 0.70f), smallW = gRightW - gGap - wideW;
        int y = gTop;
        show(forYouCard, topRow);
        show(swipeCard, topRow);
        if (topRow) {
            place(forYouCard, gRightX, y, wideW, topH);
            place(swipeCard, gRightX + wideW + gGap, y, smallW, topH);
            posterH = topH - px(16 + 14) - px(24);
            posterW = posterH * 2 / 3;
            posterSlots = Math.max(1, Math.min(5, (wideW - px(48) + px(10)) / (posterW + px(10))));
            y += topH + gRowGap;
        }
        int bottomH = rows == 2 ? gBottom - y : full;
        show(recentCard, cont);
        show(jarvisCard, jarvisOk);
        int recentW = 0;
        if (cont && jarvisOk) {
            recentW = wideW;
            place(recentCard, gRightX, y, wideW, bottomH);
            place(jarvisCard, gRightX + wideW + gGap, y, smallW, bottomH);
        } else if (cont) {
            recentW = gRightW;
            place(recentCard, gRightX, y, gRightW, bottomH);
        } else if (jarvisOk) {
            place(jarvisCard, gRightX, y, gRightW, bottomH);
        }
        if (recentW > 0) {
            int inner = recentW - px(48);
            recentSlots = inner >= px(4 * 96 + 3 * 10) ? 4 : 3;
            recentItemW = (inner - (recentSlots - 1) * px(10)) / recentSlots;
            recentImgH = Math.min(recentItemW * 9 / 16, bottomH - px(14 + 10) - px(24) - px(22));
        }
        // The frosted backdrops follow their cards to their new places.
        container.removeCallbacks(frostTask);
        container.postDelayed(frostTask, 400);
    }

    private static void show(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private static void place(View v, int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.leftMargin == x && lp.topMargin == y && lp.width == w && lp.height == h) return;
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    private PersonalizePanel personalize;
    private String cityName = "";

    private void openPersonalize() {
        if (!(activity instanceof MainActivity)) return;
        MainActivity home = (MainActivity) activity;
        if (personalize == null) {
            personalize = new PersonalizePanel(activity, (FrameLayout) container.getParent(), new PersonalizePanel.Host() {
                @Override public String greetingName() { return home.greetingName(); }
                @Override public void setGreetingName(String name) { home.setGreetingName(name); }
                @Override public String city() { return cityName; }
                @Override public void cityChanged() { new Thread(CardHome.this::readWeather, "card-weather").start(); }
            });
        }
        personalize.open();
    }

    /** Back on Home with the panel open closes it first. */
    boolean closePanel() {
        if (personalize == null || !personalize.isOpen()) return false;
        personalize.close();
        return true;
    }

    /** Writes one key into the pushed theme's config.json (the owner's own choices, such as greeting_name). */
    static void writeString(Context ctx, String key, String value) {
        try {
            File file = new File(Config.dir(ctx), Config.FILE_NAME);
            JSONObject json = file.isFile()
                    ? new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
            json.put(key, value);
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), json.toString(2).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) Files.write(file.toPath(), json.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.w("tvhome", "config write", e);
        }
    }

    /** Jarvis can answer: installed, a key set, and the key not refused by Gemini. */
    private boolean jarvisReady() {
        try (Cursor c = activity.getContentResolver().query(JARVIS_STATUS, null, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(0) == 1;
        } catch (RuntimeException e) {
            return false;   // not installed, or an older Jarvis without the status provider
        }
    }

    /**
     * The app grid scrolled by {@code offset} px (Down from the dock into the rows below). The cards move up with
     * it and fade out over the first third of the screen, as Apple TV's top section does, instead of tiles
     * sliding over them; they come back as the grid scrolls home.
     */
    void onGridScrolled(int offset) {
        // As on Apple TV: moving into the rows below, the cards go up with the page and fade into the background,
        // dim and a little smaller, still there behind the rows; back on the dock they come forward again. The
        // grid jumps a row at a time, so this animates to where the scroll puts them.
        boolean away = offset > px(40);
        // How many rows down: measured from the first real scroll (one row of tiles), so it follows whatever
        // tile size and however many rows the owner ends up with. One row down the cards sit dim behind the rows;
        // each row further they fade more, until they have gone up off the screen.
        if (away && rowStep <= 0) rowStep = offset;
        float rows = away && rowStep > 0 ? offset / (float) rowStep : 0f;
        float y = away ? -offset : 0f;
        float alpha = !away ? 1f : rows <= 1.05f ? 0.22f : Math.max(0f, 0.22f * (float) Math.pow(0.35, rows - 1));
        if (alpha < 0.03f) alpha = 0f;
        float scale = !away ? 1f : 0.96f - 0.02f * Math.min(2f, Math.max(0f, rows - 1));
        boolean settled = container.getTranslationY() == y && container.getAlpha() == alpha
                && container.getScaleX() == scale && container.getVisibility() == View.VISIBLE;
        if (shelfAway == away && settled) return;
        shelfAway = away;
        container.animate().cancel();
        container.setVisibility(View.VISIBLE);
        container.setPivotX(container.getWidth() / 2f);
        container.setPivotY(0f);
        // Dimmed behind the rows they must not take focus or clicks.
        container.setDescendantFocusability(away ? android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                : android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        container.animate().alpha(alpha).translationY(y).scaleX(scale).scaleY(scale).setDuration(away ? 420 : 360)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /**
     * Apple TV moves focus along fixed rows; Android's nearest-view search jumped between cards unpredictably
     * (Right from the clock could end on the gear). Two rows across the cards:
     *   clock -> For you posters -> Swipe        (Right at the end stays put)
     *   clock -> Continue items  -> Jarvis
     * Up and Down keep the column between the rows; Down from the bottom row goes to the dock, Up from the top
     * row to the header buttons (both left to Android: the dock and header are outside the cards).
     */
    private void wireFocus() {
        java.util.List<View> top = new java.util.ArrayList<>(), bottom = new java.util.ArrayList<>();
        if (forYouCard.getVisibility() == View.VISIBLE) {
            for (int i = 0; i < forYouRow.getChildCount(); i++) if (forYouRow.getChildAt(i).isFocusable()) top.add(forYouRow.getChildAt(i));
            if (swipeCard.getVisibility() == View.VISIBLE) top.add(swipeCard);
        }
        if (recentCard.getVisibility() == View.VISIBLE && recentRow.getVisibility() == View.VISIBLE) {
            for (int i = 0; i < recentRow.getChildCount(); i++) if (recentRow.getChildAt(i).isFocusable()) bottom.add(recentRow.getChildAt(i));
        }
        if (jarvisCard.getVisibility() == View.VISIBLE) bottom.add(jarvisCard);
        View clock = leftCardView;
        for (View v : new View[]{clock}) idOf(v);
        for (View v : top) idOf(v);
        for (View v : bottom) idOf(v);
        wireRow(clock, top);
        wireRow(clock, bottom);
        // The clock: Right into the top row (or the bottom row when there is no top row).
        java.util.List<View> first = !top.isEmpty() ? top : bottom;
        clock.setNextFocusRightId(first.isEmpty() ? clock.getId() : first.get(0).getId());
        clock.setNextFocusLeftId(clock.getId());
        // Columns: each top item goes Down to the bottom item nearest under it, and back Up.
        for (View t : top) {
            View b = nearestByX(t, bottom);
            if (b != null) t.setNextFocusDownId(b.getId());
        }
        for (View b : bottom) {
            View t = nearestByX(b, top);
            if (t != null) b.setNextFocusUpId(t.getId());
        }
    }

    /** Left and Right along one row, the clock before its first item and nothing after its last. */
    private static void wireRow(View clock, java.util.List<View> row) {
        for (int i = 0; i < row.size(); i++) {
            View v = row.get(i);
            v.setNextFocusLeftId(i == 0 ? clock.getId() : row.get(i - 1).getId());
            v.setNextFocusRightId(i == row.size() - 1 ? v.getId() : row.get(i + 1).getId());
        }
    }

    private static View nearestByX(View from, java.util.List<View> candidates) {
        if (candidates.isEmpty()) return null;
        int[] a = new int[2], b = new int[2];
        from.getLocationOnScreen(a);
        float cx = a[0] + from.getWidth() / 2f;
        View best = null;
        float bestD = Float.MAX_VALUE;
        for (View c : candidates) {
            c.getLocationOnScreen(b);
            float d = Math.abs(b[0] + c.getWidth() / 2f - cx);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private static void idOf(View v) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
    }

    /** One row of scroll in px, learnt from the first move into the rows (onGridScrolled). */
    private int rowStep;

    /** The cards are slid away for the app rows (onGridScrolled). */
    private boolean shelfAway;

    /** The greeting line the launcher already works out (time of day, name, festival). */
    void setGreeting(CharSequence text) {
        greeting.setText(text);
    }

    /** Called on every return to Home: rebuilds the recent row and, at most every few minutes, the weather. */
    void refresh() {
        container.removeCallbacks(frostTask);
        container.postDelayed(frostTask, 1200);
        container.postDelayed(frostTask, 6000);
        new Thread(() -> {
            try {
                readDiscover();
            } catch (RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("tvhome", "cards", e);   // the cards keep what they showed
            }
        }, "card-discover").start();
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
                long updated = c.getLong(c.getColumnIndexOrThrow("updated"));
                if (System.currentTimeMillis() - updated < WEATHER_MAX_AGE_MS) {
                    temp = String.format(Locale.getDefault(), "%.0f\u00b0", c.getFloat(c.getColumnIndexOrThrow("temp_c")));
                    cityName = c.getString(c.getColumnIndexOrThrow("city"));
                    text = c.getString(c.getColumnIndexOrThrow("text")) + " \u00b7 " + cityName;
                }
            }
        } catch (RuntimeException ignored) {
            // No weather add-on, or it is not answering: the line stays hidden.
        }
        final String t = temp, x = text;
        activity.runOnUiThread(() -> {
            boolean on = !t.isEmpty();
            weatherTemp.setText(t);
            weatherText.setText(x);
            weatherDivider.setVisibility(on ? View.VISIBLE : View.GONE);
            weatherLine.setVisibility(on ? View.VISIBLE : View.GONE);
        });
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
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            activity.startActivity(launch, zoom());
            opening(pkg);
        }, true);
        return item;
    }

    /** For you posters and the Swipe card's stack and count, from the TV Discover add-on. Off the main thread. */
    private void readDiscover() {
        boolean installed = installed(DISCOVER_PKG);
        java.util.List<String[]> picks = new java.util.ArrayList<>();   // {title, pkg, poster uri}
        java.util.List<String> queuePosters = new java.util.ArrayList<>();
        int queued = 0;
        java.util.List<Recent> rows = new java.util.ArrayList<>();
        if (installed) {
            try (Cursor c = activity.getContentResolver().query(CONTINUE, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    Recent r = new Recent();
                    r.title = c.getString(c.getColumnIndexOrThrow("title"));
                    r.pkg = c.getString(c.getColumnIndexOrThrow("pkg"));
                    r.intent = c.getString(c.getColumnIndexOrThrow("intent"));
                    r.progress = c.getInt(c.getColumnIndexOrThrow("progress"));
                    r.posterUri = c.getString(c.getColumnIndexOrThrow("poster"));
                    int e = c.getColumnIndex("engaged");
                    r.when = e < 0 ? 0L : c.getLong(e);
                    rows.add(r);
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
        java.util.List<Recent> recent = mergeRecent(rows);
        for (Recent r : recent) if (r.intent != null) r.poster = decodePoster(r.posterUri);
        final int count = queued;
        boolean anyPoster = false;
        for (android.graphics.Bitmap b : art) anyPoster |= b != null;
        final boolean discoverOk = installed && anyPoster;
        final boolean jarvisOk = jarvisReady();
        activity.runOnUiThread(() -> {
            relayout(discoverOk, !recent.isEmpty(), jarvisOk);
            if (discoverOk) showDiscover(installed, picks, art, stackArt, count);
            showContinue(recent);
            // Columns are matched by on-screen position, so only once the new items are laid out.
            container.post(this::wireFocus);
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
            makeFocusable(v, () -> launch(p[1]), true);
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

    /** One item of the Continue card: a Watch Next row (intent set) or an app used lately (launch set). */
    private static final class Recent {
        String title, pkg, intent, posterUri;
        int progress;
        long when;
        android.graphics.Bitmap poster;
        Drawable art;
        Intent launch;
    }

    /** Packages that are part of this TV setup rather than something the owner watches. */
    static final java.util.Set<String> NOT_RECENT = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.gathin.tvhome", "com.gathin.tvdiscover", "com.gathin.tvjarvis", "com.gathin.tvweather",
            "com.android.tv.settings", "com.google.android.tvlauncher", "com.klevico.monet", "com.mitv.tvhome.atv",
            "com.google.android.katniss", "com.wolf.google.lm", "com.android.vending", "android"));

    /**
     * Every app used lately, however it was opened (Android's usage stats, which need usage access granted from
     * the shell; without it, the apps Home opened), merged with the Watch Next rows apps publish. One item per app:
     * its row when it has one, so it resumes that title. Newest first.
     */
    private java.util.List<Recent> mergeRecent(java.util.List<Recent> rows) {
        java.util.Map<String, Long> last = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        try {
            android.app.usage.UsageStatsManager usm = activity.getSystemService(android.app.usage.UsageStatsManager.class);
            java.util.List<android.app.usage.UsageStats> stats = usm == null ? null
                    : usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 14L * 24 * 3600 * 1000, now);
            if (stats != null) for (android.app.usage.UsageStats u : stats) {
                if (u.getLastTimeUsed() <= 0 || u.getTotalTimeInForeground() <= 0) continue;
                last.merge(u.getPackageName(), u.getLastTimeUsed(), Long::max);
            }
        } catch (RuntimeException ignored) {
            // No usage access: fall back below.
        }
        if (last.isEmpty()) for (String pkg : LastOpened.recent(activity, 12)) last.put(pkg, LastOpened.get(activity, pkg));

        java.util.Map<String, Recent> byPkg = new java.util.LinkedHashMap<>();
        for (Recent r : rows) {   // newest row per app (rows come newest first)
            if (!byPkg.containsKey(r.pkg)) byPkg.put(r.pkg, r);
        }
        PackageManager pm = activity.getPackageManager();
        for (java.util.Map.Entry<String, Long> e : last.entrySet()) {
            String pkg = e.getKey();
            if (NOT_RECENT.contains(pkg)) continue;
            Recent row = byPkg.get(pkg);
            if (row != null) {
                row.when = Math.max(row.when, e.getValue());
                continue;
            }
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) continue;   // not a TV app (services, system parts)
            Recent r = new Recent();
            r.pkg = pkg;
            r.when = e.getValue();
            r.launch = launch;
            try {
                r.title = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                r.art = pm.getActivityBanner(launch);
                if (r.art == null) r.art = pm.getApplicationBanner(pkg);
                if (r.art == null) r.art = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
                continue;
            }
            byPkg.put(pkg, r);
        }
        java.util.List<Recent> out = new java.util.ArrayList<>(byPkg.values());
        out.sort((a, b) -> Long.compare(b.when, a.when));
        return out.size() > recentSlots ? new java.util.ArrayList<>(out.subList(0, recentSlots)) : out;
    }

    /** The bottom-left card: what was watched last, left to right; a row resumes its title, an app comes back. */
    private void showContinue(java.util.List<Recent> items) {
        recentTitle.setText("Continue");
        recentRow.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            Recent r = items.get(i);
            View item;
            if (r.intent != null) {
                LinearLayout v = new LinearLayout(activity);
                v.setOrientation(LinearLayout.VERTICAL);
                v.setPadding(px(4), px(4), px(4), px(4));
                FrameLayout frame = new FrameLayout(activity);
                ImageView image = posterView();
                if (r.poster != null) image.setImageBitmap(r.poster);
                frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
                int progress = Math.max(0, Math.min(100, r.progress));
                if (progress > 0) {
                    View bar = new View(activity);
                    bar.setBackgroundColor(0xFFE50914);
                    frame.addView(bar, new FrameLayout.LayoutParams((recentItemW - px(8)) * progress / 100, px(3),
                            Gravity.BOTTOM | Gravity.START));
                }
                v.addView(frame, new LinearLayout.LayoutParams(-1, recentImgH));
                TextView name = text(12, 0xCCFFFFFF, "sans-serif", 0.02f);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setText(r.title);
                name.setGravity(Gravity.CENTER_HORIZONTAL);
                LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
                nameLp.topMargin = px(4);
                v.addView(name, nameLp);
                makeFocusable(v, () -> openIntent(r.intent, r.pkg), true);
                item = v;
            } else {
                item = recentItem(r.pkg, r.title, r.art, r.launch);
            }
            recentRow.addView(item, i == 0 ? first() : next());
        }
        recentHint.setText("Apps you watch show up here");
        recentHint.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        recentRow.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
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
            activity.startActivity(i, zoom());
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
        // plain_glass: the tint alone over the wallpaper. Over a smooth wallpaper (the mesh) the blur looks the same
        // as the wallpaper itself, and the frost was a second textured pass over every card on every frame: the GPU
        // was the slow part of each frame on this TV (about 17 ms of a 16.7 ms budget).
        if (plainGlass) return;
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
        private final View owner;
        private final GradientDrawable glass;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private android.graphics.Bitmap shaded;

        Frost(View owner, GradientDrawable glass) {
            this.owner = owner;
            this.glass = glass;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            glass.setBounds(b);
            rect.set(b);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Bitmap f = frost;
            if (f != null) {
                // The blurred screen as the fill of the rounded card: one draw, no clip path (which this TV's GPU
                // renders slowly, and every card redrew it whenever one of them moved).
                if (shaded != f) {
                    shaded = f;
                    paint.setShader(new android.graphics.BitmapShader(f, android.graphics.Shader.TileMode.CLAMP,
                            android.graphics.Shader.TileMode.CLAMP));
                }
                matrix.setScale(FROST, FROST);
                matrix.postTranslate(-owner.getLeft() + getBounds().left, -owner.getTop() + getBounds().top);
                paint.getShader().setLocalMatrix(matrix);
                canvas.drawRoundRect(rect, px(14), px(14), paint);
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
        activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), zoom());
        opening(pkg);
    }

    private void openSwipe() {
        try {
            activity.startActivity(new Intent().setClassName(DISCOVER_PKG, DISCOVER_PKG + ".SwipeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), zoom());
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
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), zoom());
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
        makeFocusable(v, onOk, false);
    }

    /** parallax: posters and Continue items tilt in from the direction focus came from, then drift gently. */
    private void makeFocusable(View v, Runnable onOk, boolean parallax) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> {
            // An app opened from Home is still starting: a second press would open another one.
            if (activity instanceof MainActivity && ((MainActivity) activity).launching()) return;
            pressed = x;
            Sounds.select();
            onOk.run();
            pressed = null;
        });
        if (tvos) {
            Sheen sheen = new Sheen();
            v.setForeground(sheen);
            v.setClipToOutline(true);
            v.setOnFocusChangeListener((view, focused) -> {
                sheen.setFocused(focused, true);
                if (focused) Sounds.tick();
                if (view == jarvisCard && orb != null) orb.setActive(focused);
                if (parallax) parallax(view, focused);
                // A shadow only under solid artwork: under see-through glass (the cards, a Continue item with its
                // name) Android draws it inside the glass too, which showed as a second, lighter box on the TV.
                float lift = view instanceof ImageView ? px(18) : 0f;
                view.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                        .translationZ(focused ? lift : 0f).setDuration(focused ? 320 : 200)
                        .setInterpolator(focused ? new android.view.animation.OvershootInterpolator(0.9f)
                                : new android.view.animation.DecelerateInterpolator(1.6f)).start();
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
        c.setBackground(new Frost(c, fill));
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

        /**
         * Breathes only while its card has focus: a pulse that never stopped redrew Home on every frame, which on
         * a 1 GB TV made other motion stutter (76% of frames over 16 ms measured with it running).
         */
        void setActive(boolean on) {
            if (on) {
                if (pulse == null) {
                    pulse = ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, 1.08f);
                    pulse.setDuration(2400);
                    pulse.setRepeatCount(ValueAnimator.INFINITE);
                    pulse.setRepeatMode(ValueAnimator.REVERSE);
                    pulse.addUpdateListener(a -> setScaleY(getScaleX()));
                }
                if (!pulse.isStarted()) pulse.start();
            } else if (pulse != null) {
                pulse.cancel();
                animate().scaleX(1f).scaleY(1f).setDuration(200).start();
            }
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
