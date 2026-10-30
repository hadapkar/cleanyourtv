package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/**
 * tvos_focus: the light on a focused tile, as on Apple TV. A soft top light while focused, and once, as focus
 * lands, a diagonal glint that sweeps across. Set as the view's foreground; the view's outline clips it.
 */
final class Sheen extends Drawable {
    private final Paint top = new Paint();
    private final Paint glint = new Paint();
    private final ValueAnimator sweep = ValueAnimator.ofFloat(-0.6f, 1.6f);
    private float at = 2f;   // glint position across the tile; off the tile when idle
    private final android.graphics.Matrix shift = new android.graphics.Matrix();
    private LinearGradient band;
    private float bandFor = -1f;
    private boolean on;

    Sheen() {
        sweep.setDuration(700);
        sweep.setInterpolator(new DecelerateInterpolator(1.4f));
        sweep.addUpdateListener(a -> {
            at = (float) a.getAnimatedValue();
            invalidateSelf();
        });
    }

    /** Focus gained or lost. Gaining runs the glint once. */
    void setFocused(boolean focused, boolean animate) {
        on = focused;
        sweep.cancel();
        at = 2f;
        if (focused && animate) sweep.start();
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        top.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        if (!on) return;
        Rect b = getBounds();
        canvas.drawRect(b, top);
        if (at < -0.5f || at > 1.5f) return;
        float w = b.width(), half = w * 0.28f;
        // One gradient per tile size, slid across with a matrix: a new one every frame of the sweep was garbage
        // the 1 GB TV had to collect mid-animation.
        if (band == null || bandFor != w) {
            band = new LinearGradient(-half, b.top, half, b.bottom,
                    new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            bandFor = w;
            glint.setShader(band);
        }
        shift.setTranslate(b.left + w * at, 0f);
        band.setLocalMatrix(shift);
        canvas.drawRect(b, glint);
    }

    @Override public void setAlpha(int alpha) { top.setAlpha(alpha); glint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { top.setColorFilter(cf); glint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
