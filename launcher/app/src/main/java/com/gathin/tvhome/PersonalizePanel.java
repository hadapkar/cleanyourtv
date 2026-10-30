package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * OK (or a hold) on the clock card: a glass panel to change the greeting's name and the weather's city. The name
 * is saved into the theme's config.json on the TV; the city goes to the TV Weather add-on, which looks it up
 * itself (the launcher stays offline). Back or Done closes it.
 */
final class PersonalizePanel {
    private static final Uri WEATHER = Uri.parse("content://com.gathin.tvweather");

    interface Host {
        String greetingName();
        void setGreetingName(String name);
        String city();
        void cityChanged();
    }

    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final float d;
    private FrameLayout overlay;
    private TextView status;
    private EditText nameField, cityField;
    private View doneButton;
    private String nameAtOpen = "", cityAtOpen = "";
    /** Root children whose focus was blocked while the panel is open, with what they had. */
    private final java.util.Map<android.view.ViewGroup, Integer> blocked = new java.util.HashMap<>();

    PersonalizePanel(Activity activity, FrameLayout root, Host host) {
        this.activity = activity;
        this.root = root;
        this.host = host;
        this.d = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() {
        return overlay != null;
    }

    void open() {
        if (overlay != null) return;
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0x99050814);
        overlay.setAlpha(0f);
        overlay.setClickable(true);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(32), px(26), px(32), px(24));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF0262B3C, 0xF0161A26});
        bg.setCornerRadius(px(20));
        bg.setStroke(Math.max(1, px(1)), 0x59FFFFFF);
        card.setBackground(bg);
        card.setElevation(px(24));
        overlay.addView(card, new FrameLayout.LayoutParams(px(520), -2, Gravity.CENTER));

        TextView title = text(26, 0xFFFFFFFF, "sans-serif-light");
        title.setText("Personalize");
        card.addView(title);
        TextView sub = text(14, 0x99FFFFFF, "sans-serif");
        sub.setText("OK to edit \u00b7 Done saves \u00b7 Back cancels");
        card.addView(sub, margins(4));

        EditText name = field("Greeting name", host.greetingName(), card, InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText city = field("Weather city", host.city(), card, InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameField = name;
        cityField = city;
        nameAtOpen = host.greetingName();
        cityAtOpen = host.city();
        // The keyboard's OK only moves on (name, then city, then Done); nothing is kept until Done.
        name.setOnEditorActionListener((v, action, e) -> {
            if (!isDone(action, e)) return false;
            hideKeyboard(v);
            city.requestFocus();
            return true;
        });
        city.setOnEditorActionListener((v, action, e) -> {
            if (!isDone(action, e)) return false;
            hideKeyboard(v);
            if (doneButton != null) doneButton.requestFocus();
            return true;
        });

        status = text(14, 0xCCFFFFFF, "sans-serif");
        card.addView(status, margins(14));
        TextView done = text(17, 0xFFFFFFFF, "sans-serif-medium");
        done.setText("Done");
        done.setGravity(Gravity.CENTER);
        done.setPadding(px(18), px(12), px(18), px(12));
        done.setBackground(rowBackground());
        done.setFocusable(true);
        done.setOnClickListener(v -> close(true));
        doneButton = done;
        card.addView(done, margins(14));

        overlay.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                close(false);
                return true;
            }
            return false;
        });
        for (View v : new View[]{name, city, done}) {
            v.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    close(false);
                    return true;
                }
                return false;
            });
        }
        // Only the panel takes focus while it is open: the remote must not wander onto Home behind it.
        blocked.clear();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) child;
                blocked.put(g, g.getDescendantFocusability());
                g.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
        }
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.animate().alpha(1f).setDuration(200).start();
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f)).start();
        name.requestFocus();
    }

    /** Back on Home cancels, like Back inside the panel. */
    void close() {
        close(false);
    }

    /** save: Done keeps what was typed; Back (save false) leaves everything as it was. */
    void close(boolean save) {
        if (overlay == null) return;
        if (save) saveChanges();
        for (java.util.Map.Entry<android.view.ViewGroup, Integer> e : blocked.entrySet()) {
            e.getKey().setDescendantFocusability(e.getValue());
        }
        blocked.clear();
        FrameLayout o = overlay;
        overlay = null;
        hideKeyboard(o);
        o.animate().alpha(0f).setDuration(160).withEndAction(() -> root.removeView(o)).start();
    }

    /** Done keeps what was typed: a new name at once, a new city looked up by TV Weather. */
    private void saveChanges() {
        String name = nameField == null ? nameAtOpen : nameField.getText().toString().trim();
        if (!name.equals(nameAtOpen)) host.setGreetingName(name);
        String city = cityField == null ? cityAtOpen : cityField.getText().toString().trim();
        if (city.isEmpty() || city.equalsIgnoreCase(cityAtOpen)) return;
        new Thread(() -> {
            try {
                Bundle out = activity.getContentResolver().call(WEATHER, "set_city", city, null);
                if (out != null && out.getString("city") != null) activity.runOnUiThread(host::cityChanged);
            } catch (RuntimeException ignored) {
                // No weather add-on.
            }
        }, "personalize-city").start();
    }

    private EditText field(String label, String value, LinearLayout card, int capitalise) {
        TextView l = text(13, 0xB3FFFFFF, "sans-serif-medium");
        l.setText(label.toUpperCase(java.util.Locale.getDefault()));
        l.setLetterSpacing(0.18f);
        card.addView(l, margins(20));
        EditText e = new EditText(activity);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | capitalise);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0x66FFFFFF);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        e.setPadding(px(16), px(10), px(16), px(10));
        e.setBackground(rowBackground());
        // The keyboard waits for OK: focus alone must not open it, or the arrows type into the field.
        e.setShowSoftInputOnFocus(false);
        e.setOnClickListener(v -> {
            InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        });
        card.addView(e, margins(6));
        return e;
    }

    private static boolean isDone(int action, KeyEvent e) {
        return action == EditorInfo.IME_ACTION_DONE
                || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void say(String line) {
        if (status != null) status.setText(line);
    }

    private StateListDrawable rowBackground() {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(12));
        on.setColor(0x33FFFFFF);
        on.setStroke(px(2), 0xE6FFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(px(12));
        off.setColor(0x14FFFFFF);
        off.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(top);
        return lp;
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }
}
