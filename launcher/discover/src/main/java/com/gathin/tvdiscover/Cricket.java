package com.gathin.tvdiscover;

import android.util.Xml;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Live and recent cricket scores from ESPNcricinfo's public RSS feed (no key; the feed asks for a refresh every
 * two minutes). Each item's title is "Team A 185/10 & 172/10 * v Team B 112/10", the star marking the side batting.
 * India's matches come first, then the IPL, then other internationals, then the rest, in the feed's order.
 */
final class Cricket {
    private static final String FEED = "https://static.cricinfo.com/rss/livescores.xml";
    private static final long FRESH_MS = 2 * 60 * 1000L;
    /** A failed fetch keeps showing the last scores this long, then shows none. */
    private static final long STALE_MS = 30 * 60 * 1000L;
    private static final Set<String> NATIONS = new HashSet<>(Arrays.asList(
            "India", "Australia", "England", "South Africa", "New Zealand", "Pakistan", "Sri Lanka", "Bangladesh",
            "West Indies", "Afghanistan", "Zimbabwe", "Ireland", "Scotland", "Netherlands", "Nepal", "Oman",
            "United Arab Emirates", "Namibia", "United States of America", "USA", "Hong Kong", "Canada"));
    /** IPL (and WPL, the same franchises): next after India. */
    private static final Set<String> IPL = new HashSet<>(Arrays.asList(
            "Mumbai Indians", "Chennai Super Kings", "Royal Challengers Bengaluru", "Royal Challengers Bangalore",
            "Kolkata Knight Riders", "Sunrisers Hyderabad", "Rajasthan Royals", "Delhi Capitals", "Punjab Kings",
            "Lucknow Super Giants", "Gujarat Titans", "Gujarat Giants", "UP Warriorz"));

    private static List<JSONObject> last = Collections.emptyList();
    private static long fetched, succeeded;

    private Cricket() {
    }

    static synchronized List<JSONObject> matches() {
        long now = System.currentTimeMillis();
        if (now - fetched < FRESH_MS) return last;
        fetched = now;
        try {
            last = parse(fetch());
            succeeded = now;
        } catch (Exception e) {
            if (now - succeeded > STALE_MS) last = Collections.emptyList();
        }
        return last;
    }

    private static List<String> fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(FEED).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            List<String> titles = new ArrayList<>();
            boolean inItem = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG && "item".equals(p.getName())) inItem = true;
                else if (e == XmlPullParser.END_TAG && "item".equals(p.getName())) inItem = false;
                else if (e == XmlPullParser.START_TAG && inItem && "title".equals(p.getName())) titles.add(p.nextText());
            }
            return titles;
        } finally {
            c.disconnect();
        }
    }

    private static List<JSONObject> parse(List<String> titles) throws JSONException {
        List<JSONObject> india = new ArrayList<>(), ipl = new ArrayList<>(), intl = new ArrayList<>(), rest = new ArrayList<>();
        for (String title : titles) {
            String[] sides = title.split("\\s+v\\s+", 2);
            if (sides.length != 2) continue;
            String[] a = side(sides[0]), b = side(sides[1]);
            if (a[0].isEmpty() || b[0].isEmpty()) continue;
            JSONObject m = new JSONObject();
            m.put("team1", a[0]).put("score1", a[1]).put("team2", b[0]).put("score2", b[1]);
            m.put("batting", !a[2].isEmpty() ? 1 : !b[2].isEmpty() ? 2 : 0);
            boolean ind = nation(a[0]).equals("India") || nation(b[0]).equals("India");
            boolean league = IPL.contains(nation(a[0])) || IPL.contains(nation(b[0]));
            boolean both = NATIONS.contains(nation(a[0])) && NATIONS.contains(nation(b[0]));
            m.put("priority", ind ? 3 : league ? 2 : both ? 1 : 0);
            (ind ? india : league ? ipl : both ? intl : rest).add(m);
        }
        List<JSONObject> out = new ArrayList<>(india);
        out.addAll(ipl);
        out.addAll(intl);
        out.addAll(rest);
        return out;
    }

    /** {team, score, "*" when batting}: the team is the words before the first number. */
    private static String[] side(String s) {
        s = s.trim();
        String bat = "";
        if (s.endsWith("*")) {
            bat = "*";
            s = s.substring(0, s.length() - 1).trim();
        }
        String[] words = s.split("\\s+");
        int i = 0;
        while (i < words.length && !Character.isDigit(words[i].charAt(0))) i++;
        String team = String.join(" ", Arrays.copyOfRange(words, 0, i));
        String score = String.join(" ", Arrays.copyOfRange(words, i, words.length)).replaceAll("\\s*&\\s*", " & ");
        return new String[]{team, score, bat};
    }

    /** "India Women", "India A" and "India U19" count as India. */
    private static String nation(String team) {
        return team.replaceAll("\\s+(Women|A|U19|Under-19|Legends)$", "");
    }
}
