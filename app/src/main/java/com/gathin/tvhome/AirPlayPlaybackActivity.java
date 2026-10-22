package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/** Full-screen surface for AirPlay mirroring. Starts the UxPlay-based receiver and renders the
 *  decoded H.264/H.265 to the SurfaceView. Back returns to the desktop. */
public final class AirPlayPlaybackActivity extends Activity implements SurfaceHolder.Callback, AirPlayReceiver.Listener {
    private SurfaceView surfaceView;
    private SelfDrawnUi.TextLine status;
    private boolean started;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        status = new SelfDrawnUi.TextLine(this, getString(R.string.airplay_waiting), SelfDrawnUi.TextLine.SECTION);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = SelfDrawnUi.dpi(this, 24);
        root.addView(status, lp);
        setContentView(root);
        AirPlayReceiver.get().setListener(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        AirPlayReceiver.get().setSurface(holder.getSurface());
        startReceiver();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {
        AirPlayReceiver.get().setSurface(holder.getSurface());
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        AirPlayReceiver.get().setSurface(null);
    }

    private void startReceiver() {
        if (started) return;
        started = true;
        String name = getString(R.string.airplay_name);
        new Thread(() -> {
            final boolean ok = AirPlayReceiver.get().start(this, name, 1920, 1080);
            runOnUiThread(() -> status.setText(getString(ok ? R.string.airplay_waiting : R.string.airplay_failed)));
        }, "airplay-start").start();
    }

    @Override public void onConnected() {
        runOnUiThread(() -> status.setText(getString(R.string.airplay_connected)));
    }
    @Override public void onDisconnected() {
        runOnUiThread(() -> status.setText(getString(R.string.airplay_waiting)));
    }

    @Override public void onBackPressed() {
        AirPlayReceiver.get().stop();
        finish();
    }
}
