package com.gathin.tvdiscover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Stores the TMDB read token sent from the adb shell (the manifest requires DUMP, which only the shell and
 * the system hold), then fetches the catalogue once. The token never ships inside the APK.
 */
public class TokenReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String token = intent.getStringExtra("token");
        if (token == null || token.trim().isEmpty()) {
            setResultData("missing token");
            return;
        }
        Catalog.setToken(context, token);
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                boolean ok = Catalog.refresh(app);
                pending.setResultData(ok ? "ok " + Catalog.all(app).size() + " titles" : "token saved, refresh failed");
            } finally {
                pending.finish();
            }
        }, "discover-token").start();
    }
}
