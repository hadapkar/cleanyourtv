package com.gathin.tvhome;

import android.app.ActivityOptions;
import android.os.Bundle;
import android.view.View;

/** tvos_focus: the opening app's window grows out of the tile or card that was pressed, as on Apple TV. */
final class Zoom {
    private Zoom() {
    }

    /** Options for startActivity, or null (no view, or a view not laid out yet). */
    static Bundle from(View v) {
        if (v == null || v.getWidth() == 0 || v.getHeight() == 0) return null;
        return ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
    }
}
