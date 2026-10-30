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
import android.speech.tts.TextToSpeech;
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
import java.util.Locale;

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
    /** Speaks each answer with the TV's own (offline) voice; null until it is ready, or when there is none. */
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    /**
     * Every call into the TV's speech engine runs here: on this TV a language check could take seconds, and on the
     * main thread that froze Jarvis until Android closed it (input dispatching timed out).
     */
    private final java.util.concurrent.ExecutorService voice = java.util.concurrent.Executors.newSingleThreadExecutor();
    private float d;
    private TextView hints;
    /** One automatic retry when nothing was heard, so a slow start to speaking is not an error. */
    private boolean retried;
    /** When listening last started (uptime ms), so a burst of presses starts it once. */
    private long lastListen;
    /** An app about to open; Back within the short delay cancels it (a misheard name). */
    private Runnable pending;
    /** Something was playing when Jarvis opened over it: Back plays it again (players pause when covered). */
    private android.media.session.MediaController wasPlaying;
    /** Without session access (low-RAM TVs): audio was playing when Jarvis opened. */
    private boolean audioWasPlaying;
    /** Spoken names to the package they mean, beyond the apps' own labels. */
    private static final String[][] ALIASES = {
            {"hotstar", "in.startv.hotstar"}, {"jio hotstar", "in.startv.hotstar"}, {"prime", "com.amazon.amazonvideo.livingroom"},
            {"amazon", "com.amazon.amazonvideo.livingroom"}, {"sony", "com.sonyliv"}, {"sony liv", "com.sonyliv"},
            {"coco flix", "com.app.bluesun"}, {"cocoflix", "com.app.bluesun"}, {"cricfy", "app.cricfy.tv"},
            {"youtube music", "com.google.android.youtube.tvmusic"}, {"you tube", "com.google.android.youtube.tv"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics m = getResources().getDisplayMetrics();
        d = m.density;
        FrameLayout root = new FrameLayout(this);
        // Over whatever was on screen: the window is translucent, so the app stays visible behind a deep frosted
        // tint. (Android 9 has no window blur; Android 12 added it.)
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD9050814, 0xE60B1230, 0xF2050814}));
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(220).start();

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
        hints = label(15, 0x99FFFFFF, "sans-serif");
        hints.setText("Try  \u201COpen Netflix\u201D   \u00b7   \u201CPlay Arijit Singh songs\u201D   \u00b7   "
                + "\u201CSuggest a comedy\u201D   \u00b7   \u201CWeather in Mumbai\u201D");
        col.addView(hints, centred(30));
        TextView keys = label(13, 0x80FFFFFF, "sans-serif");
        keys.setText("OK  Ask again      Back  Close");
        col.addView(keys, centred(18));
        setContentView(root);
        android.media.session.MediaController under = Actions.playing(this);
        if (under != null && under.getPlaybackState() != null
                && under.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING) wasPlaying = under;
        audioWasPlaying = under == null && Actions.audioPlaying(this);
        tts = new TextToSpeech(this, st -> ttsReady = st == TextToSpeech.SUCCESS);
        voice.execute(() -> { /* warm the thread */ });

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
        // Presses closer together than this are one press (a bouncing key, or OK just after the overlay opened).
        if (android.os.SystemClock.uptimeMillis() - lastListen < 700) return;
        if (tts != null) voice.execute(() -> {
            try {
                tts.stop();
            } catch (RuntimeException ignored) {
                // Not bound yet.
            }
        });
        cancelPending();
        hints.setVisibility(View.VISIBLE);
        choices.removeAllViews();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
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
        // A session still open (OK pressed while listening) is ended first; otherwise the service ignores the new one.
        recognizer.cancel();
        lastListen = android.os.SystemClock.uptimeMillis();
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
            // Google's recognizer reports the session listen() just cancelled as a client error; the new one runs.
            if (error == SpeechRecognizer.ERROR_CLIENT && android.os.SystemClock.uptimeMillis() - lastListen < 1500) return;
            orb.setMode(OrbView.IDLE);
            boolean nothingHeard = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (nothingHeard && !retried) {
                retried = true;
                status.setText("Still listening");
                handler.postDelayed(JarvisActivity.this::listen, 300);
                return;
            }
            showState(nothingHeard ? "Didn't catch that" : "Couldn't listen", "", friendly(error));
        }

        @Override
        public void onResults(Bundle results) {
            List<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) onError(SpeechRecognizer.ERROR_NO_MATCH);
            else understood(r);
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
        if (resultCode == RESULT_OK && r != null && !r.isEmpty()) understood(r);
        else showState("Didn't catch that", "", "Press OK and try again.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) listen();
        else showState("Microphone off", "", "Jarvis needs the microphone to hear you.");
    }

    // ---- thinking and acting ---------------------------------------------------------------------

    /** What each speech error means for the owner, and what to do. */
    private static String friendly(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Press OK and speak right after the beep, with the remote in front of you.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return "Google's speech service is not reachable. Check the TV's internet and press OK.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "The remote's microphone is not sending sound. Press OK to try again.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "The speech service is busy. Wait a second and press OK.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Jarvis is not allowed to use the microphone.";
            default:
                return "Something went wrong with listening. Press OK to try again.";
        }
    }

    private void understood(List<String> alternatives) {
        retried = false;
        hints.setVisibility(View.GONE);
        String said = alternatives.get(0);
        heard.setText("\u201C" + said + "\u201D");
        // "Open Netflix", "Hotstar kholo": a known app and an open word need no round trip to Gemini.
        String local = localOpen(alternatives);
        if (local != null) {
            openSoon(local);
            return;
        }
        status.setText("Thinking");
        orb.setMode(OrbView.THINKING);
        List<String> apps = installedApps();
        new Thread(() -> {
            try {
                JSONObject a = Gemini.ask(this, alternatives, apps, catalogue(), Actions.context(this));
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
        String action = str(a, "action");
        String app = str(a, "app");
        String query = str(a, "query");
        status.setText("Jarvis");
        reply.setText(str(a, "say"));
        // Spoken only when there is something to hear; opening an app speaks for itself.
        boolean ownLine = action.matches("open_app|search|volume|settings|media|navigate|screensaver|reminder|find_title|list_add|list_remove|list_show");
        if (!ownLine) speak(str(a, "say"));
        switch (action) {
            case "open_app":
                if (!installed(app)) reply.setText("That app is not on this TV.");
                else openSoon(app);
                break;
            case "search":
                if (!search(app, query)) reply.setText("That app is not on this TV.");
                break;
            case "options":
                showChoices(a.optJSONArray("options"));
                break;
            case "set_city":
                setCity(str(a, "city"));
                break;
            case "volume":
                volume(str(a, "volume"), a.optInt("level", 0));
                break;
            case "settings":
                showIfAny(Actions.settings(this, str(a, "page")));
                break;
            case "media":
                showIfAny(Actions.media(this, str(a, "command"), a.optInt("seconds", 0)));
                // Back to what is playing.
                handler.postDelayed(this::finish, 700);
                break;
            case "navigate": {
                String to = str(a, "to");
                if (to.equals("back")) finish();
                else if (Actions.launcher(this, to.equals("apps") ? "library" : to, 0)) finish();
                break;
            }
            case "sleep": {
                int m = a.optInt("minutes", 0);
                Actions.launcher(this, m == 0 ? "sleep" : "sleep_in", Math.max(0, m));
                if (m == 0) finish();
                break;
            }
            case "screensaver":
                if (Actions.launcher(this, "screensaver", 0)) finish();
                break;
            case "reminder":
                showIfAny(Actions.reminder(this, a.optInt("minutes", 0), str(a, "time"), str(a, "text")));
                break;
            case "find_title":
                findTitle(str(a, "query"), str(a, "app"));
                break;
            case "list_add":
            case "list_remove":
            case "list_show":
                myList(action, str(a, "query"));
                break;
            default:
                break;
        }
    }

    /** The package of an app named together with an open word ("open", "launch", "start", "kholo", "chalao"). */
    private String localOpen(List<String> alternatives) {
        PackageManager pm = getPackageManager();
        List<String[]> names = new ArrayList<>();   // {normalised name, package}
        for (String line : installedApps()) {
            int eq = line.lastIndexOf('=');
            names.add(new String[]{norm(line.substring(0, eq)), line.substring(eq + 1)});
        }
        for (String[] a : ALIASES) names.add(new String[]{norm(a[0]), a[1]});
        for (String said : alternatives) {
            String s = " " + norm(said) + " ";
            // Anything about content goes to Gemini: "play Family Man on Netflix" is a search, not an open.
            if (s.matches(".*\\b(play|search|find|show me|suggest|song|songs|gaana|gaane|movie|film|episode|watch)\\b.*")) return null;
            boolean openWord = s.matches(".*\\b(open|launch|start|kholo|khol|chalao|chalu|run|go to)\\b.*");
            String flat = s.replace(" ", "");
            String best = null;
            int bestLen = 0;
            for (String[] n : names) {
                String key = n[0].replace(" ", "");
                if (key.length() < 3 || !flat.contains(key) || key.length() <= bestLen) continue;
                try {
                    pm.getApplicationInfo(n[1], 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                best = n[1];
                bestLen = key.length();
            }
            // A bare app name ("Netflix") counts too.
            if (best != null && (openWord || flat.length() <= bestLen + 4)) return best;
        }
        return null;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean installed(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Shows "Opening <app>" for a moment, so Back can stop a misheard name, then opens it. */
    private void openSoon(String pkg) {
        orb.setMode(OrbView.IDLE);
        status.setText("Opening");
        reply.setText(label(pkg) + "  \u00b7  Back to cancel");
        cancelPending();
        pending = () -> {
            pending = null;
            if (!launch(pkg)) reply.setText("That app is not on this TV.");
        };
        handler.postDelayed(pending, 900);
    }

    private void cancelPending() {
        if (pending == null) return;
        handler.removeCallbacks(pending);
        pending = null;
    }

    @Override
    public void onBackPressed() {
        if (pending != null) {
            cancelPending();
            showState("Cancelled", heard.getText().toString(), "Press OK to ask again.");
            return;
        }
        // Back to the app underneath, playing again if it was.
        if (wasPlaying != null) {
            try {
                wasPlaying.getTransportControls().play();
            } catch (RuntimeException ignored) {
                // The session ended meanwhile.
            }
        } else if (audioWasPlaying) {
            // After the window is gone, so the play key reaches the app again in front.
            Context app = getApplicationContext();
            handler.postDelayed(() -> Actions.media(app, "play", 0), 400);
        }
        super.onBackPressed();
    }

    /** "Title (Service)" for titles TV Discover knows, so Gemini can send a title to the app that has it. */
    private List<String> catalogue() {
        List<String> out = new ArrayList<>();
        try (android.database.Cursor c = getContentResolver().query(
                android.net.Uri.parse("content://com.gathin.tvdiscover/catalogue"), null, null, null, null)) {
            while (c != null && c.moveToNext() && out.size() < 80) {
                out.add(c.getString(c.getColumnIndexOrThrow("title")) + " (" + c.getString(c.getColumnIndexOrThrow("service")) + ")");
            }
        } catch (RuntimeException ignored) {
            // TV Discover not installed or not set up: Gemini goes by what it knows.
        }
        return out;
    }

    /** optString turns a JSON null into the word "null"; models send null for empty fields. */
    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    /** Hindi in Devanagari gets the Hindi voice, everything else Indian English. */
    private void speak(String text) {
        if (!ttsReady || text == null || text.isEmpty()) return;
        boolean hindi = text.codePoints().anyMatch(c -> c >= 0x0900 && c <= 0x097F);
        Locale want = hindi ? new Locale("hi", "IN") : new Locale("en", "IN");
        voice.execute(() -> {
            try {
                if (tts.isLanguageAvailable(want) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(want);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
            } catch (RuntimeException ignored) {
                // The engine went away (Jarvis closing): nothing to say.
            }
        });
    }

    private void showIfAny(String line) {
        if (line == null || line.isEmpty()) return;
        reply.setText(line);
        speak(line);
    }

    /** "Where is Pathaan": TMDB says exactly where it streams; one app opens it, several are offered, none is said. */
    private void findTitle(String query, String app) {
        status.setText("Looking it up");
        orb.setMode(OrbView.THINKING);
        new Thread(() -> {
            JSONObject t = Actions.lookup(this, query);
            handler.post(() -> {
                orb.setMode(OrbView.IDLE);
                status.setText("Jarvis");
                if (t == null) {
                    showIfAny("I couldn't find " + query + ".");
                    return;
                }
                String name = t.optString("title") + (t.optString("year").isEmpty() ? "" : " (" + t.optString("year") + ")");
                JSONArray services = t.optJSONArray("services");
                int n = services == null ? 0 : services.length();
                if (n == 0) {
                    JSONArray other = t.optJSONArray("elsewhere");
                    String where = other != null && other.length() > 0 ? other.optString(0) : "";
                    showIfAny(name + " isn't on your apps" + (where.isEmpty() ? "." : ". It's on " + where + "."));
                    return;
                }
                // The app the owner named wins when it has the title.
                for (int i = 0; i < n; i++) {
                    JSONObject s = services.optJSONObject(i);
                    if (s != null && s.optString("pkg").equals(app)) {
                        showIfAny(name + " is on " + s.optString("name") + ".");
                        search(app, t.optString("title"));
                        return;
                    }
                }
                if (n == 1) {
                    JSONObject s = services.optJSONObject(0);
                    showIfAny(name + " is on " + s.optString("name") + ".");
                    search(s.optString("pkg"), t.optString("title"));
                    return;
                }
                JSONArray options = new JSONArray();
                for (int i = 0; i < n; i++) {
                    JSONObject s = services.optJSONObject(i);
                    try {
                        options.put(new JSONObject().put("title", t.optString("title")).put("app", s.optString("pkg"))
                                .put("why", s.optString("name")));
                    } catch (org.json.JSONException ignored) {
                        // Keys and values are plain strings.
                    }
                }
                showIfAny(name + " is on " + n + " of your apps. Pick one.");
                showChoices(options);
            });
        }, "jarvis-find").start();
    }

    private void myList(String action, String query) {
        new Thread(() -> {
            String line;
            JSONArray show = null;
            if (action.equals("list_show")) {
                show = Actions.myList(this);
                line = show.length() == 0 ? "Your list is empty." : "Here's your list.";
            } else if (action.equals("list_remove")) {
                line = Actions.listRemove(this, query) > 0 ? "Removed from your list." : "That isn't on your list.";
            } else {
                JSONObject t = Actions.listAdd(this, query);
                line = t == null ? "I couldn't find " + query + "."
                        : t.optString("title") + " is on your list" + (t.optString("service").isEmpty() ? "."
                        : ", on " + t.optString("service") + ".");
            }
            final String l = line;
            final JSONArray s = show;
            handler.post(() -> {
                showIfAny(l);
                if (s != null && s.length() > 0) showChoices(s);
            });
        }, "jarvis-list").start();
    }

    /** The TV's media volume: mute, unmute, up, down, or set to a percentage. No permission needed. */
    private void volume(String what, int level) {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        int stream = android.media.AudioManager.STREAM_MUSIC, ui = android.media.AudioManager.FLAG_SHOW_UI;
        switch (what) {
            case "mute": am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, ui); break;
            case "unmute": am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, ui); break;
            case "up":
                for (int i = 0; i < 3; i++) am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, i == 2 ? ui : 0);
                break;
            case "down":
                for (int i = 0; i < 3; i++) am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, i == 2 ? ui : 0);
                break;
            case "set":
                int max = am.getStreamMaxVolume(stream);
                am.setStreamVolume(stream, Math.round(max * Math.max(0, Math.min(100, level)) / 100f), ui);
                break;
            default:
                break;
        }
    }

    /** Looks the city up (Open-Meteo geocoding, no key) and hands it to the TV Weather add-on. */
    private void setCity(String city) {
        if (city == null || city.trim().isEmpty()) return;
        new Thread(() -> {
            String msg;
            try {
                java.net.URL url = new java.net.URL("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name="
                        + java.net.URLEncoder.encode(city.trim(), "UTF-8"));
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                String body;
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    body = out.toString("UTF-8");
                } finally {
                    c.disconnect();
                }
                JSONArray results = new JSONObject(body).optJSONArray("results");
                if (results == null || results.length() == 0) {
                    msg = "I could not find " + city + ".";
                } else {
                    JSONObject place = results.getJSONObject(0);
                    Bundle extras = new Bundle();
                    extras.putDouble("lat", place.getDouble("latitude"));
                    extras.putDouble("lon", place.getDouble("longitude"));
                    Bundle ok = getContentResolver().call(android.net.Uri.parse("content://com.gathin.tvweather"),
                            "set_place", place.getString("name"), extras);
                    msg = ok == null ? "The weather app did not accept the change."
                            : "Weather now shows " + place.getString("name") + ".";
                }
            } catch (Exception e) {
                msg = "I could not change the city right now.";
            }
            final String m = msg;
            handler.post(() -> {
                reply.setText(m);
                speak(m);
            });
        }, "jarvis-city").start();
    }

    private void showChoices(JSONArray options) {
        choices.removeAllViews();
        if (options == null) return;
        for (int i = 0; i < options.length() && i < 5; i++) {
            JSONObject o = options.optJSONObject(i);
            if (o == null) continue;
            String title = str(o, "title"), app = str(o, "app");
            if (title.isEmpty()) continue;
            TextView card = label(17, 0xFFFFFFFF, "sans-serif-medium");
            card.setText(title + "\n" + label(app) + (str(o, "why").isEmpty() ? "" : " · " + str(o, "why")));
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
     * The most accurate search each app allows, as measured on the MiTV-AESP0: YouTube opens its results page from
     * a link; apps with an in-app search (JioHotstar, VLC) take ACTION_SEARCH; Netflix, Prime Video and SonyLIV take
     * neither, so Google TV search shows the title with a "Watch on ..." button that opens it in the right app.
     */
    private boolean search(String pkg, String query) {
        if (pkg == null || pkg.isEmpty() || !installed(pkg)) return false;
        if (query == null || query.trim().isEmpty()) return launch(pkg);
        PackageManager pm = getPackageManager();
        try {
            if (pkg.equals("com.google.android.youtube.tv")) {
                Intent yt = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query="
                        + java.net.URLEncoder.encode(query, "UTF-8"))).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!pm.queryIntentActivities(yt, 0).isEmpty()) {
                    startActivity(yt);
                    return true;
                }
            }
        } catch (java.io.UnsupportedEncodingException | RuntimeException ignored) {
            // Try the next route.
        }
        Intent own = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!pm.queryIntentActivities(own, 0).isEmpty()) {
            try {
                startActivity(own);
                return true;
            } catch (RuntimeException ignored) {
                // Try Google TV search.
            }
        }
        Intent google = new Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!pm.queryIntentActivities(google, 0).isEmpty()) {
            try {
                startActivity(google);
                return true;
            } catch (RuntimeException ignored) {
                // Fall back to opening the app.
            }
        }
        if (!launch(pkg)) return false;
        android.widget.Toast.makeText(this, "Search for \u201C" + query + "\u201D in " + label(pkg),
                android.widget.Toast.LENGTH_LONG).show();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        boolean voiceKey = keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST;
        // A held key repeats about 20 times a second: each repeat restarted listening, which the speech service
        // refused as already running, and Jarvis flashed and never heard anything. Only the first press counts.
        if ((ok || voiceKey) && event.getRepeatCount() > 0) return true;
        if (ok && (choices.getChildCount() == 0 || !choices.hasFocus())) {
            if (!Gemini.key(this).isEmpty()) listen();
            return true;
        }
        if (voiceKey) {
            listen();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) {
            TextToSpeech t = tts;
            // By the time the voice thread gets here the activity may be gone, and unbinding the speech engine
            // throws "Service not registered": that crashed Jarvis just after it closed.
            voice.execute(() -> {
                try {
                    t.shutdown();
                } catch (RuntimeException ignored) {
                    // Already unbound with the activity.
                }
            });
        }
        voice.shutdown();
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
