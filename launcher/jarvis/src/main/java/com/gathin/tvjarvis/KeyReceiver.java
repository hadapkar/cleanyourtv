package com.gathin.tvjarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Stores the Gemini API key (and optionally a model name) sent from the adb shell. The manifest requires DUMP,
 * which only the shell and the system hold, so the key never ships in the APK and no app can change it.
 */
public class KeyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String key = intent.getStringExtra("key");
        if (key == null || key.trim().isEmpty()) {
            setResultData("missing key");
            return;
        }
        Gemini.setKey(context, key, intent.getStringExtra("model"));
        setResultData("ok");
    }
}
