package com.gathin.tvjarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Turns what the owner said into one TV action with Gemini. The request carries the spoken sentence and the
 * names of the installed apps, nothing else; the reply is JSON in the shape {@link #SYSTEM} describes.
 */
final class Gemini {
    private static final String PREFS = "jarvis";
    // Google's aliases for the current Flash models; fixed names such as gemini-2.5-flash are retired for new
    // keys. The light one answers in about a second, which matters more on a TV than the last bit of quality.
    static final String DEFAULT_MODEL = "gemini-flash-lite-latest";
    /** Tried when the first model is busy (429/5xx): slower, but usually free when the light one is not. */
    private static final String FALLBACK_MODEL = "gemini-flash-latest";

    private static final String SYSTEM = "You are Jarvis, the voice assistant of an Android TV in India. "
            + "The owner speaks English, Hindi or Hinglish. Reply with one JSON object only, no prose, in this shape: "
            + "{\"say\": short friendly sentence to show on screen, "
            + "\"action\": one of \"open_app\", \"search\", \"options\", \"set_city\", \"volume\", \"settings\", "
            + "\"media\", \"navigate\", \"sleep\", \"screensaver\", \"reminder\", \"find_title\", \"list_add\", "
            + "\"list_remove\", \"list_show\", \"answer\", "
            + "\"app\": package name from the installed list or \"\", "
            + "\"query\": what to search for or \"\", \"city\": a city name for set_city or \"\", "
            + "\"volume\": for volume one of \"mute\", \"unmute\", \"up\", \"down\", \"set\" or \"\", "
            + "\"level\": 0-100 for volume set, else 0, "
            + "\"page\": for settings one of wifi, bluetooth, display, sound, apps, storage, date, language, network, "
            + "accessibility, about, main; "
            + "\"command\": for media one of play, pause, toggle, next, previous, rewind, forward, stop; "
            + "\"seconds\": seconds to rewind or forward, else 0; "
            + "\"to\": for navigate one of home, back, apps, switcher; "
            + "\"minutes\": minutes for sleep (0 = now, -1 = cancel the timer) or for a reminder from now, else 0; "
            + "\"time\": for a reminder at a clock time, 24-hour HH:mm, else \"\"; "
            + "\"text\": what to be reminded of, else \"\"; "
            + "\"options\": up to 5 objects {\"title\": a film or show title, \"app\": package name, \"why\": four words} }. "
            + "open_app: the owner wants an app. search: the owner wants a specific film, show, song or video; pick the "
            + "app most likely to have it (YouTube for songs, clips and trailers). options: the owner asks for "
            + "suggestions; give real titles available in India on the listed apps. set_city: the owner wants the weather "
            + "for another city from now on. answer: anything else; put the "
            + "answer in say, at most two sentences. Only use package names from the list. Option titles must be real "
            + "films or shows, never categories such as \"Netflix thrillers\". Use \"\" rather than null. "
            + "If the owner names an app for a search, use that app; otherwise pick the app that streams the title "
            + "(see the list of titles when it has it). Keep say under 15 words. Examples: "
            + "\"play family man\" -> search, Prime Video, query \"The Family Man\"; "
            + "\"arijit singh ke gaane lagao\" -> search, YouTube, query \"Arijit Singh songs\"; "
            + "\"kuch comedy dikhao\" -> options with five comedies; "
            + "\"mumbai ka weather\" -> set_city, city \"Mumbai\"; "
            + "\"mute\" / \"awaaz band karo\" -> volume mute; \"awaaz badhao\" -> volume up; \"volume 20 pe karo\" -> volume set, level 20; "
            + "\"bluetooth kholo\" -> settings bluetooth; \"pause karo\" -> media pause; \"10 second peeche\" -> media "
            + "rewind, seconds 10; \"next episode\" -> media next; \"home pe jao\" -> navigate home; \"meri apps "
            + "dikhao\" -> navigate apps; \"30 minute mein TV band karo\" -> sleep, minutes 30; \"TV band karo\" -> "
            + "sleep, minutes 0; \"screensaver chalao\" -> screensaver; \"8 baje match ke liye yaad dilana\" -> "
            + "reminder, time 20:00, text Match; \"Pathaan kahan hai\" / \"find Pathaan\" -> find_title, query "
            + "Pathaan; \"Family Man meri list mein daalo\" -> list_add, query The Family Man; \"meri list dikhao\" -> "
            + "list_show; \"what's playing\" / \"aaj ka mausam\" / \"time kya hua\" -> answer, from the TV context. "
            + "\"who won the last world cup\" -> answer.";

    private Gemini() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String key(Context ctx) {
        return prefs(ctx).getString("key", "");
    }

    static void setKey(Context ctx, String key, String model) {
        // A new key starts trusted again.
        SharedPreferences.Editor e = prefs(ctx).edit().putString("key", key.trim()).putBoolean("refused", false);
        if (model != null && !model.trim().isEmpty()) e.putString("model", model.trim());
        e.apply();
    }

    /**
     * Blocking. {heard} are the recognizer's guesses, best first; {apps} are "Label=package" lines; {titles} are
     * "Title (Service)" lines streaming on this TV, possibly empty. Throws with a readable message on any failure.
     */
    static JSONObject ask(Context ctx, List<String> heard, List<String> apps, List<String> titles, String tv)
            throws IOException, JSONException {
        String key = key(ctx);
        if (key.isEmpty()) throw new IOException("Gemini is not set up yet");
        String model = prefs(ctx).getString("model", DEFAULT_MODEL);
        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray()
                        .put(new JSONObject().put("text", SYSTEM))))
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                        .put(new JSONObject().put("text", prompt(heard, apps, titles, tv))))))
                .put("generationConfig", new JSONObject().put("responseMimeType", "application/json")
                        .put("temperature", 0.2));
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            // A busy first model hands the later attempts to the fallback.
            String use = attempt == 0 || !(last instanceof Busy) ? model : FALLBACK_MODEL;
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + use + ":generateContent");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                // The key travels in a header, so it never appears in a URL or a log line.
                c.setRequestProperty("x-goog-api-key", key);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = c.getResponseCode();
                InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
                String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
                if (status >= 400) {
                    String msg = new JSONObject(text.isEmpty() ? "{}" : text).optJSONObject("error") == null ? ""
                            : new JSONObject(text).getJSONObject("error").optString("message");
                    if (status == 429 || status >= 500) {
                        last = new Busy("Gemini is busy (" + status + ")");
                        continue;
                    }
                    // A refused key (401/403) or a model that no longer exists (404) will not fix itself: the
                    // launcher hides the Jarvis card until a new key is set.
                    if (status == 401 || status == 403 || status == 404) prefs(ctx).edit().putBoolean("refused", true).apply();
                    throw new Refused("Gemini said: " + (msg.isEmpty() ? "error " + status : msg));
                }
                String reply = new JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                prefs(ctx).edit().putBoolean("refused", false).apply();
                return new JSONObject(stripFence(reply));
            } catch (Refused e) {
                throw e;
            } catch (IOException e) {
                last = e;
            } finally {
                c.disconnect();
            }
            try {
                Thread.sleep(1200L * (attempt + 1));
            } catch (InterruptedException e) {
                break;
            }
        }
        throw last != null ? last : new IOException("No answer from Gemini");
    }

    private static String prompt(List<String> heard, List<String> apps, List<String> titles, String tv) {
        StringBuilder p = new StringBuilder("The TV right now:\n").append(tv).append("\n\nInstalled apps:\n").append(String.join("\n", apps));
        if (!titles.isEmpty()) {
            p.append("\n\nSome titles streaming on these apps in India now:\n").append(String.join("\n", titles));
        }
        p.append("\n\nSpeech recognition heard (best guess first; it may mishear names, pick the most sensible):\n");
        for (int i = 0; i < heard.size(); i++) p.append(i + 1).append(". ").append(heard.get(i)).append('\n');
        return p.toString();
    }

    /** The model is overloaded or rate-limited; another model may answer. */
    private static final class Busy extends IOException {
        Busy(String message) {
            super(message);
        }
    }

    /** An answer that retrying will not change. */
    private static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    /** Models sometimes wrap JSON in ```json fences despite the mime type. */
    private static String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) t = t.substring(nl + 1, end);
        }
        return t.trim();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
