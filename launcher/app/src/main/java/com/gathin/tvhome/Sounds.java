package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * focus_sounds (theme): a soft tick when focus moves and a click on OK, as on Apple TV. The sounds are
 * synthesised (tools-tv/make_sounds.js) and played quietly through a small SoundPool loaded once.
 */
final class Sounds {
    private static SoundPool pool;
    private static int tick, select;
    private static boolean on;
    private static long lastTick;

    private Sounds() {
    }

    static void init(Context ctx) {
        on = CardHome.flag(ctx, "focus_sounds");
        if (!on || pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        tick = pool.load(ctx.getApplicationContext(), R.raw.focus_tick, 1);
        select = pool.load(ctx.getApplicationContext(), R.raw.focus_select, 1);
    }

    /** Focus moved. Rapid moves (a held arrow) tick at most every 60 ms. */
    static void tick() {
        if (!on || pool == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTick < 60) return;
        lastTick = now;
        pool.play(tick, 0.35f, 0.35f, 0, 0, 1f);
    }

    /** OK pressed on something that opens. */
    static void select() {
        if (!on || pool == null) return;
        pool.play(select, 0.45f, 0.45f, 0, 0, 1f);
    }
}
