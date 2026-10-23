package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/**
 * Auto-shown full-screen mirror surface for AirPlay. Uses a plain SurfaceView decoded by the TV's
 * hardware MediaCodec (driven by AirPlayReceiver). Auto-launched when an iPhone starts mirroring;
 * closes when it disconnects. Back also closes.
 */
public final class AirPlayPlaybackActivity extends Activity implements AirPlayReceiver.Listener, SurfaceHolder.Callback {
    private SurfaceView surfaceView;
    private SelfDrawnUi.TextLine status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        status = new SelfDrawnUi.TextLine(this, getString(R.string.airplay_connected), SelfDrawnUi.TextLine.SECTION);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = SelfDrawnUi.dpi(this, 24);
        root.addView(status, lp);
        setContentView(root);
        AirPlayReceiver.get().setListener(this);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        AirPlayReceiver.get().setSurface(this, h.getSurface());
        if (!AirPlayReceiver.get().isStarted()) {
            new Thread(() -> AirPlayReceiver.get().start(this, getString(R.string.airplay_name), 1920, 1080), "airplay-start").start();
        }
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
        AirPlayReceiver.get().setSurface(this, h.getSurface());
    }
    @Override public void surfaceDestroyed(SurfaceHolder h) { AirPlayReceiver.get().clearSurface(); }

    @Override public void onConnected() { runOnUiThread(() -> status.setText(getString(R.string.airplay_connected))); }
    @Override public void onDisconnected() { runOnUiThread(() -> finish()); }

    @Override public void onBackPressed() { finish(); }
}
