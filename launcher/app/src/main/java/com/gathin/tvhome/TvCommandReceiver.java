package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * Commands from Jarvis that need the launcher: sleep now, sleep in N minutes (or cancel), the screensaver, Home,
 * the app library and the app switcher. The manifest guards it with a signature permission, so only apps
 * signed with this launcher's key (Jarvis) can send them.
 */
public class TvCommandReceiver extends BroadcastReceiver {
    static final String ACTION = "com.gathin.tvhome.COMMAND";

    @Override
    public void onReceive(Context context, Intent intent) {
        String command = intent.getStringExtra("command");
        if (command == null) return;
        Context ctx = context.getApplicationContext();
        switch (command) {
            case "sleep":
                // Standby goes through the key helper (an accessibility service may lock the screen, Android 9+).
                Context helper = HomeKeyService.current();
                if (helper == null) {
                    setResultData("no key helper");
                    return;
                }
                RemoteKeys.runAction(helper, "sleep");
                break;
            case "sleep_in": {
                int minutes = intent.getIntExtra("minutes", 0);
                AlarmManager alarms = ctx.getSystemService(AlarmManager.class);
                PendingIntent sleep = sleepIntent(ctx);
                if (alarms == null) return;
                alarms.cancel(sleep);
                if (minutes > 0) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + minutes * 60_000L, sleep);
                    Toast.makeText(ctx, ctx.getString(R.string.sleep_timer_set, minutes), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(ctx, R.string.sleep_timer_off, Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case "screensaver":
                RemoteKeys.runAction(ctx, "screensaver");
                break;
            case "home":
                start(ctx, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.getPackageName()));
                break;
            case "library":
                start(ctx, new Intent(Intent.ACTION_ALL_APPS).setClass(ctx, MainActivity.class));
                break;
            case "switcher":
                start(ctx, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(ctx, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_SWITCHER, true));
                break;
            default:
                setResultData("unknown");
                return;
        }
        setResultData("ok");
    }

    private static PendingIntent sleepIntent(Context ctx) {
        Intent i = new Intent(ctx, TvCommandReceiver.class).setAction(ACTION).putExtra("command", "sleep");
        return PendingIntent.getBroadcast(ctx, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // Nothing to open it with.
        }
    }
}
