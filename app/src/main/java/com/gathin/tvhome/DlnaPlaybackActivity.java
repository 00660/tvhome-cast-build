package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

/** Full-screen renderer for media pushed over DLNA. Kept as its own Activity so the phone's
 *  "cast" can bring the video up over the launcher; Back returns to the desktop. */
public final class DlnaPlaybackActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surfaceView;
    private SelfDrawnUi.TextLine hint;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        hint = new SelfDrawnUi.TextLine(this, getString(R.string.dlna_casting), SelfDrawnUi.TextLine.SECTION);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = SelfDrawnUi.dpi(this, 24);
        root.addView(hint, hp);
        hint.setAlpha(0.85f);

        setContentView(root);
        handleIntent(getIntent());
        DlnaPlayer.get().setListener(this::refreshHint);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("uri")) {
            DlnaPlayer.get().setUri(intent.getStringExtra("uri"));
        }
    }

    private void refreshHint() {
        if (hint == null) return;
        String s = DlnaPlayer.get().getState();
        hint.setText(getString("PLAYING".equals(s) ? R.string.dlna_casting
                : "TRANSITIONING".equals(s) ? R.string.dlna_loading : R.string.dlna_casting));
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        DlnaPlayer.get().setSurface(holder.getSurface());
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        DlnaPlayer.get().setSurface(holder.getSurface());
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        DlnaPlayer.get().clearSurface();
    }

    @Override protected void onResume() {
        super.onResume();
        if (surfaceView != null && surfaceView.getHolder().getSurface() != null
                && surfaceView.getHolder().getSurface().isValid()) {
            DlnaPlayer.get().setSurface(surfaceView.getHolder().getSurface());
        }
    }

    @Override public void onBackPressed() {
        DlnaPlayer.get().stop();
        DlnaPlayer.get().release();
        finish();
    }
}
