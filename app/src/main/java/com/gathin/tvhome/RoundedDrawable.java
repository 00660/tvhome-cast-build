package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Draws a drawable with rounded corners, working on every API level (Android 4.4 included) by
 * clipping to a round-rect in software drawing — no clipToOutline (API 21+) needed. With
 * centerCrop the source is scaled to COVER the bounds (preserving aspect ratio, so never
 * stretched), clipped to the bounds and rounded — so the icon fills the tile without spilling
 * outside it and without square corners.
 */
final class RoundedDrawable extends Drawable {
    private final Drawable source;
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();

    RoundedDrawable(Drawable source, float radius) {
        this.source = source;
        this.radius = radius;
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);
    }

    @Override public void draw(Canvas canvas) {
        int w = getBounds().width();
        int h = getBounds().height();
        if (w <= 0 || h <= 0) return;
        Bitmap bmp = toBitmap(source, w, h);
        if (bmp == null) { source.draw(canvas); return; }

        BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        // Cover: uniform scale so the bitmap fills the bounds (aspect ratio preserved).
        float sx = (float) w / bmp.getWidth();
        float sy = (float) h / bmp.getHeight();
        float scale = Math.max(sx, sy);
        matrix.setScale(scale, scale);
        matrix.postTranslate((w - bmp.getWidth() * scale) / 2f, (h - bmp.getHeight() * scale) / 2f);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);

        canvas.save();
        rect.set(getBounds());
        canvas.clipRect(rect);
        canvas.drawRoundRect(rect, radius, radius, paint);
        canvas.restore();
    }

    private static Bitmap toBitmap(Drawable d, int w, int h) {
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) return b;
        }
        try {
            int iw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : w;
            int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : h;
            Bitmap b = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, iw, ih);
            d.draw(c);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
