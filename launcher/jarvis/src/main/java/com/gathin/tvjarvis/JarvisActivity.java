package com.gathin.tvjarvis;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Jarvis: listens through the remote's microphone, shows the words as they come, asks Gemini what to do and
 * does it, or offers a few choices to pick with the remote. OK listens again, Back leaves.
 */
public class JarvisActivity extends Activity {
    private static final int REQ_MIC = 1;
    private static final int REQ_SPEECH = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OrbView orb;
    private TextView status;
    private TextView heard;
    private TextView reply;
    private LinearLayout choices;
    private SpeechRecognizer recognizer;
    private float d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics m = getResources().getDisplayMetrics();
        d = m.density;
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF050814, 0xFF0B1230, 0xFF050814}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        orb = new OrbView(this);
        int orbSize = (int) (m.heightPixels * 0.34f);
        col.addView(orb, new LinearLayout.LayoutParams(orbSize, orbSize));
        status = label(14, 0x99FFFFFF, "sans-serif-medium");
        status.setLetterSpacing(0.25f);
        status.setAllCaps(true);
        heard = label(30, 0xFFFFFFFF, "sans-serif-light");
        reply = label(20, 0xCCFFFFFF, "sans-serif");
        reply.setMaxLines(3);
        col.addView(status, centred(18));
        col.addView(heard, centred(10));
        col.addView(reply, centred(14));
        choices = new LinearLayout(this);
        choices.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(choices, centred(28));
        TextView keys = label(13, 0x80FFFFFF, "sans-serif");
        keys.setText("OK  Ask again      Back  Close");
        col.addView(keys, centred(36));
        setContentView(root);

        if (Gemini.key(this).isEmpty()) {
            showState("Not set up", "", "Add the Gemini key from the laptop, then try again.");
            return;
        }
        listen();
    }

    private TextView label(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private LinearLayout.LayoutParams centred(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = (int) (top * d);
        return lp;
    }

    private void showState(String s, String h, String r) {
        status.setText(s);
        heard.setText(h);
        reply.setText(r);
    }

    // ---- listening -------------------------------------------------------------------------------

    private void listen() {
        choices.removeAllViews();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // No recognition service to bind to: the system's own voice screen still may exist.
            try {
                startActivityForResult(intent, REQ_SPEECH);
            } catch (RuntimeException e) {
                showState("No speech service", "", "This TV has no speech recognition to listen with.");
            }
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        showState("Listening", "", "");
        orb.setMode(OrbView.LISTENING);
        recognizer.startListening(intent);
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { status.setText("Listening"); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { orb.setLevel(rmsdB); }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { orb.setMode(OrbView.THINKING); }

        @Override
        public void onError(int error) {
            orb.setMode(OrbView.IDLE);
            showState("Didn't catch that", "",
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ? "Press OK and try again." : "Speech service error " + error + ". Press OK to retry.");
        }

        @Override
        public void onResults(Bundle results) {
            List<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) onError(SpeechRecognizer.ERROR_NO_MATCH);
            else understood(r.get(0));
        }

        @Override
        public void onPartialResults(Bundle partial) {
            List<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) heard.setText(r.get(0));
        }

        @Override public void onEvent(int eventType, Bundle params) { }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SPEECH) return;
        ArrayList<String> r = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (resultCode == RESULT_OK && r != null && !r.isEmpty()) understood(r.get(0));
        else showState("Didn't catch that", "", "Press OK and try again.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) listen();
        else showState("Microphone off", "", "Jarvis needs the microphone to hear you.");
    }

    // ---- thinking and acting ---------------------------------------------------------------------

    private void understood(String said) {
        heard.setText("“" + said + "”");
        status.setText("Thinking");
        orb.setMode(OrbView.THINKING);
        List<String> apps = installedApps();
        new Thread(() -> {
            try {
                JSONObject a = Gemini.ask(this, said, apps);
                handler.post(() -> act(a));
            } catch (Exception e) {
                handler.post(() -> {
                    orb.setMode(OrbView.IDLE);
                    showState("Something went wrong", heard.getText().toString(), String.valueOf(e.getMessage()));
                });
            }
        }, "jarvis-gemini").start();
    }

    private void act(JSONObject a) {
        orb.setMode(OrbView.IDLE);
        String action = a.optString("action");
        String app = a.optString("app");
        String query = a.optString("query");
        status.setText("Jarvis");
        reply.setText(a.optString("say"));
        switch (action) {
            case "open_app":
                if (!launch(app)) reply.setText("That app is not on this TV.");
                break;
            case "search":
                if (!search(app, query)) reply.setText("That app is not on this TV.");
                break;
            case "options":
                showChoices(a.optJSONArray("options"));
                break;
            default:
                break;
        }
    }

    private void showChoices(JSONArray options) {
        choices.removeAllViews();
        if (options == null) return;
        for (int i = 0; i < options.length() && i < 5; i++) {
            JSONObject o = options.optJSONObject(i);
            if (o == null) continue;
            String title = o.optString("title"), app = o.optString("app");
            TextView card = label(17, 0xFFFFFFFF, "sans-serif-medium");
            card.setText(title + "\n" + label(app) + (o.optString("why").isEmpty() ? "" : " · " + o.optString("why")));
            card.setPadding((int) (20 * d), (int) (14 * d), (int) (20 * d), (int) (14 * d));
            card.setBackground(cardBackground());
            card.setFocusable(true);
            card.setOnClickListener(v -> {
                if (!search(app, title)) reply.setText("That app is not on this TV.");
            });
            card.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f)
                    .setDuration(140).start());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (choices.getChildCount() > 0) lp.leftMargin = (int) (14 * d);
            choices.addView(card, lp);
        }
        if (choices.getChildCount() > 0) choices.getChildAt(0).requestFocus();
    }

    private StateListDrawable cardBackground() {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(14 * d);
        on.setColor(0x40FFFFFF);
        on.setStroke((int) (2 * d), 0xE6FFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(14 * d);
        off.setColor(0x1FFFFFFF);
        off.setStroke(Math.max(1, (int) d), 0x4DFFFFFF);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    /** "Netflix=com.netflix.ninja" for every app with a TV launcher entry. */
    private List<String> installedApps() {
        List<String> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            out.add(r.loadLabel(pm) + "=" + r.activityInfo.packageName);
        }
        return out;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private boolean launch(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    /**
     * Searches inside the app when it takes a search request (YouTube does), otherwise opens the app with the
     * title on screen, since most streaming apps accept no search from outside.
     */
    private boolean search(String pkg, String query) {
        if (pkg == null || pkg.isEmpty()) return false;
        Intent s = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!getPackageManager().queryIntentActivities(s, 0).isEmpty()) {
            try {
                startActivity(s);
                return true;
            } catch (RuntimeException ignored) {
                // Fall back to opening the app.
            }
        }
        if (!launch(pkg)) return false;
        android.widget.Toast.makeText(this, "Search for “" + query + "” in " + label(pkg),
                android.widget.Toast.LENGTH_LONG).show();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        if (ok && (choices.getChildCount() == 0 || !choices.hasFocus())) {
            if (!Gemini.key(this).isEmpty()) listen();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            listen();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        orb.setMode(OrbView.IDLE);
        super.onDestroy();
    }

    /** The moving orb: breathes when idle, swells with the voice while listening, swirls while thinking. */
    static final class OrbView extends View {
        static final int IDLE = 0, LISTENING = 1, THINKING = 2;
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        private int mode = IDLE;
        private float level, shown, phase;

        OrbView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            clock.setDuration(4000);
            clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.setInterpolator(new LinearInterpolator());
            clock.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                shown += (level - shown) * 0.25f;
                invalidate();
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            clock.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            clock.cancel();
            super.onDetachedFromWindow();
        }

        void setMode(int m) {
            mode = m;
            if (m != LISTENING) level = 0f;
        }

        /** SpeechRecognizer's level, about -2 (quiet) to 10 (loud). */
        void setLevel(float rmsdB) {
            level = Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy);
            double t = phase * Math.PI * 2;
            float breathe = (float) (0.5 + 0.5 * Math.sin(t * 2));
            float r = max * (0.42f + 0.04f * breathe + 0.16f * shown);
            int inner = mode == THINKING ? 0xFFB4A2FF : 0xFFBFD4FF;
            core.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.3f,
                    new int[]{0xFFFFFFFF, inner, 0xFF4A5BD6, 0x003040A0}, new float[]{0f, 0.35f, 0.8f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, core);
            // Three thin rings: ripple outwards while listening, orbit while thinking, drift when idle.
            for (int i = 0; i < 3; i++) {
                float p = (phase + i / 3f) % 1f;
                float rr;
                int alpha;
                if (mode == LISTENING) {
                    rr = r + (max - r) * p;
                    alpha = (int) (160 * (1 - p) * (0.4f + shown));
                } else if (mode == THINKING) {
                    rr = r * (1.12f + 0.08f * i);
                    alpha = 120;
                } else {
                    rr = r * (1.08f + 0.1f * i) + 4 * (float) Math.sin(t + i);
                    alpha = 50;
                }
                ring.setStrokeWidth(Math.max(1f, max * 0.012f));
                ring.setColor((Math.max(0, Math.min(255, alpha)) << 24) | 0xC8D6FF);
                if (mode == THINKING) {
                    float start = (float) (phase * 360 * (i % 2 == 0 ? 1 : -1.4) + i * 120);
                    canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, start, 110, false, ring);
                } else {
                    canvas.drawCircle(cx, cy, rr, ring);
                }
            }
        }
    }
}
