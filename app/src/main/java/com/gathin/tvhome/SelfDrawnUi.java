package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;

/**
 * Minimal self-drawn UI kit for the launcher settings and wallpaper screens.
 *
 * Every visible pixel (backgrounds, rows, labels, values, the status banner) is painted in
 * {@link View#onDraw}. Only invisible containers (ScrollView / LinearLayout / FrameLayout) are used
 * for layout, focus traversal and scrolling. No native Button, EditText, AlertDialog, Toast,
 * PopupWindow or Dialog is used anywhere in these screens.
 */
final class SelfDrawnUi {
    static final int BG_TOP = 0xFF0E1522;
    static final int BG_BOTTOM = 0xFF05080E;
    static final int ROW = 0xFF19222F;
    static final int ROW_FOCUS = 0xFF24394A;
    static final int ROW_DISABLED = 0xFF141B26;
    static final int ACCENT = 0xFF66D9C3;
    static final int TEXT = 0xFFFFFFFF;
    static final int SUB = 0xFFA4B1C3;
    static final int DIM = 0xFF6C7A8C;

    private SelfDrawnUi() { }

    static int dpi(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    static float sp(Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, c.getResources().getDisplayMetrics());
    }

    /** A titled / section / hint / paragraph text line, painted by hand. */
    static final class TextLine extends View {
        static final int TITLE = 0, SECTION = 1, HINT = 2, BODY = 3;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text;
        private final int kind;

        TextLine(Context c, String text, int kind) {
            super(c);
            this.text = text;
            this.kind = kind;
            paint.setAntiAlias(true);
            switch (kind) {
                case TITLE:
                    paint.setColor(TEXT);
                    paint.setTextSize(sp(c, 34));
                    paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                    break;
                case SECTION:
                    paint.setColor(ACCENT);
                    paint.setTextSize(sp(c, 16));
                    paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                    break;
                case BODY:
                    paint.setColor(SUB);
                    paint.setTextSize(sp(c, 15));
                    break;
                default:
                    paint.setColor(DIM);
                    paint.setTextSize(sp(c, 14));
                    break;
            }
            setFocusable(false);
        }

        void setText(String t) { text = t; requestLayout(); invalidate(); }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int width = MeasureSpec.getSize(wSpec);
            if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) width = 0;
            float h;
            switch (kind) {
                case TITLE: h = sp(getContext(), 34) * 1.5f; break;
                case SECTION: h = sp(getContext(), 16) * 2.6f; break;
                default: h = sp(getContext(), 15) * 1.7f; break;
            }
            setMeasuredDimension(width, (int) h);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (text == null) return;
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = (getHeight() - (fm.bottom - fm.top)) / 2f - fm.top;
            if (kind == SECTION) baseline = getHeight() - fm.bottom - sp(getContext(), 4);
            canvas.drawText(text, 0, baseline, paint);
        }
    }

    /**
     * One interactive row: label on the left, optional value (On/Off, "5 列") and chevron on the
     * right. Focus is drawn as a raised accent-bordered card. Activation is a normal click / DPAD
     * center because the view is clickable and focusable.
     */
    static final class RowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String label;
        private String value;
        private final boolean chevron;
        private boolean enabled = true;
        private boolean focused;

        RowView(Context c, String label, String value, boolean chevron) {
            super(c);
            this.label = label;
            this.value = value;
            this.chevron = chevron;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            textPaint.setAntiAlias(true);
        }

        void setLabel(String l) { label = l; invalidate(); }
        void setValue(String v) { value = v; invalidate(); }
        void setRowEnabled(boolean e) {
            enabled = e;
            setFocusable(e);
            setClickable(e);
            setAlpha(e ? 1f : 0.45f);
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int width = MeasureSpec.getSize(wSpec);
            if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) width = SelfDrawnUi.dpi(getContext(), 520);
            int height;
            if (MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY) {
                height = MeasureSpec.getSize(hSpec);
            } else {
                height = SelfDrawnUi.dpi(getContext(), 60);
            }
            setMeasuredDimension(width, height);
        }

        @Override protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect prev) {
            super.onFocusChanged(gainFocus, direction, prev);
            focused = gainFocus;
            animate().scaleX(gainFocus ? 1.02f : 1f).scaleY(gainFocus ? 1.02f : 1f)
                    .setDuration(120).start();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float pad = SelfDrawnUi.dpi(getContext(), 3);
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            float radius = SelfDrawnUi.dpi(getContext(), 14);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(!enabled ? ROW_DISABLED : (focused ? ROW_FOCUS : ROW));
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (focused && enabled) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(SelfDrawnUi.dpi(getContext(), 2));
                paint.setColor(ACCENT);
                float inset = SelfDrawnUi.dpi(getContext(), 1.5f);
                rect.inset(inset, inset);
                canvas.drawRoundRect(rect, radius - inset, radius - inset, paint);
            }

            float left = SelfDrawnUi.dpi(getContext(), 24);
            textPaint.setColor(enabled ? TEXT : DIM);
            textPaint.setTextSize(sp(getContext(), 19));
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, focused ? Typeface.BOLD : Typeface.NORMAL));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, left, baseline, textPaint);

            float right = getWidth() - SelfDrawnUi.dpi(getContext(), 24);
            if (value != null) {
                textPaint.setTextSize(sp(getContext(), 17));
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setColor(focused && enabled ? ACCENT : SUB);
                float vw = textPaint.measureText(value);
                canvas.drawText(value, right - vw, baseline, textPaint);
                right -= vw + SelfDrawnUi.dpi(getContext(), 16);
            }
            if (chevron) {
                textPaint.setColor(focused && enabled ? ACCENT : DIM);
                textPaint.setTextSize(sp(getContext(), 22));
                textPaint.setTypeface(Typeface.DEFAULT);
                float cw = textPaint.measureText("›");
                canvas.drawText("›", getWidth() - SelfDrawnUi.dpi(getContext(), 20) - cw, baseline, textPaint);
            }
        }
    }

    /** Transient status pill shown at the bottom, replacing Toast. Auto-hides. */
    static final class Banner extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private String message;
        private final RectF rect = new RectF();
        private final Runnable hide = () -> { message = null; setVisibility(INVISIBLE); invalidate(); };

        Banner(Context c) {
            super(c);
            setVisibility(INVISIBLE);
            textPaint.setAntiAlias(true);
            textPaint.setColor(TEXT);
            textPaint.setTextSize(sp(c, 17));
        }

        void show(String text, long millis) {
            message = text;
            setVisibility(VISIBLE);
            invalidate();
            handler.removeCallbacks(hide);
            handler.postDelayed(hide, millis);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (message == null) return;
            float padH = SelfDrawnUi.dpi(getContext(), 26);
            float padV = SelfDrawnUi.dpi(getContext(), 16);
            float tw = textPaint.measureText(message);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float th = fm.descent - fm.ascent;
            float w = tw + padH * 2;
            float h = th + padV * 2;
            float left = (getWidth() - w) / 2f;
            float bottom = getHeight() - SelfDrawnUi.dpi(getContext(), 40);
            rect.set(left, bottom - h, left + w, bottom);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xF01B2433);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(SelfDrawnUi.dpi(getContext(), 1));
            paint.setColor(ACCENT);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
            float baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(message, left + padH, baseline, textPaint);
        }
    }

    /** Full-screen vertical gradient background, drawn behind the settings list. */
    static final class GradientView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        GradientView(Context c) { super(c); setFocusable(false); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setShader(new android.graphics.LinearGradient(
                    0, 0, 0, getHeight(), BG_TOP, BG_BOTTOM, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    /** Self-drawn replacement for Toast: a pill in a PopupWindow. The PopupWindow is only a frame; the
     *  message itself is painted by {@link Banner}. Never uses android.widget.Toast. */
    static final class Toast {
        static void show(Context c, int resId) {
            if (c != null) show(c, c.getString(resId));
        }

        static void show(Context c, CharSequence text) {
            if (c == null || text == null || text.length() == 0) return;
            final android.widget.PopupWindow[] ref = new android.widget.PopupWindow[1];
            Banner banner = new Banner(c);
            android.widget.PopupWindow popup = new android.widget.PopupWindow(banner,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    dpi(c, 150), false);
            popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            popup.setTouchable(false);
            popup.setFocusable(false);
            popup.setOutsideTouchable(false);
            ref[0] = popup;
            banner.show(text.toString(), 2200);
            View anchor = c instanceof android.app.Activity
                    ? ((android.app.Activity) c).getWindow().getDecorView() : new View(c);
            try {
                popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, 0, 0);
            } catch (RuntimeException e) {
                return;
            }
            banner.postDelayed(() -> {
                try { popup.dismiss(); } catch (RuntimeException ignored) { }
            }, 2400);
        }
    }

    /**
     * Self-drawn two-choice prompt over an existing view tree (replaces android.app.Dialog for the
     * module-card confirmation). The scrim and panel are painted by hand; the choices are self-drawn
     * {@link RowView}s. Left/Right move between them, Back means "keep hidden".
     */
    static final class CardPrompt {
        final android.view.ViewGroup root;
        final View scrim;
        final android.widget.LinearLayout panel;
        final RowView keep;
        final RowView show;
        Runnable onKeep;
        Runnable onShow;

        CardPrompt(Context c, android.view.ViewGroup host, String title, String body, String names,
                   String identity) {
            root = host;
            scrim = new View(c) {
                private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas canvas) {
                    p.setColor(0xB3000000);
                    canvas.drawRect(0, 0, getWidth(), getHeight(), p);
                }
            };
            scrim.setClickable(true);
            scrim.setFocusable(false);

            panel = new android.widget.LinearLayout(c);
            panel.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = dpi(c, 30);
            panel.setPadding(pad, dpi(c, 26), pad, dpi(c, 22));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF1A2433);
            bg.setCornerRadius(dpi(c, 20));
            bg.setStroke(dpi(c, 1), ACCENT);
            panel.setBackgroundDrawable(bg);

            TextLine titleLine = new TextLine(c, title, TextLine.TITLE);
            panel.addView(titleLine, new android.widget.LinearLayout.LayoutParams(-1, -2));
            if (body != null && body.length() > 0) {
                TextLine b = new TextLine(c, body, TextLine.BODY);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dpi(c, 14);
                panel.addView(b, lp);
            }
            if (names != null && names.length() > 0) {
                TextLine n = new TextLine(c, names, TextLine.BODY);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dpi(c, 8);
                panel.addView(n, lp);
            }
            if (identity != null && identity.length() > 0) {
                TextLine id = new TextLine(c, identity, TextLine.HINT);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dpi(c, 10);
                panel.addView(id, lp);
            }

            keep = new RowView(c, c.getString(R.string.card_keep_hidden), null, false);
            android.widget.LinearLayout.LayoutParams klp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            klp.topMargin = dpi(c, 20);
            panel.addView(keep, klp);
            show = new RowView(c, c.getString(R.string.card_show), null, false);
            android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = dpi(c, 10);
            panel.addView(show, slp);
        }

        void present() {
            android.widget.FrameLayout.LayoutParams panelLp = new android.widget.FrameLayout.LayoutParams(
                    dpi(root.getContext(), 520), -2, android.view.Gravity.CENTER);
            root.addView(scrim, new android.widget.FrameLayout.LayoutParams(-1, -1));
            root.addView(panel, panelLp);
            scrim.setOnClickListener(v -> keep());
            keep.setOnClickListener(v -> keep());
            show.setOnClickListener(v -> show());
            android.view.View.OnKeyListener keys = (v, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) return true;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) return v == keep;
                return keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && v == show;
            };
            keep.setOnKeyListener(keys);
            show.setOnKeyListener(keys);
            keep.post(keep::requestFocus);
        }

        void dismiss() {
            if (panel.getParent() != null) root.removeView(panel);
            if (scrim.getParent() != null) root.removeView(scrim);
        }

        boolean attached() { return panel.getParent() != null; }

        void back() { keep(); }

        private void keep() { if (onKeep != null) onKeep.run(); }
        private void show() { if (onShow != null) onShow.run(); }
    }
}
