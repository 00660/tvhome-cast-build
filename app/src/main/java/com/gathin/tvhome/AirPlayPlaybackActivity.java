package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.videolan.libvlc.util.VLCVideoLayout;

/** Full-screen surface for AirPlay mirroring. Uses libVLC (software) to render the mirrored H.264
 *  and MediaCodec for audio. Back returns to the desktop. */
public final class AirPlayPlaybackActivity extends Activity implements AirPlayReceiver.Listener {
    private VLCVideoLayout videoLayout;
    private SelfDrawnUi.TextLine status;
    private boolean started;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(-1, -1));
        status = new SelfDrawnUi.TextLine(this, getString(R.string.airplay_waiting), SelfDrawnUi.TextLine.SECTION);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = SelfDrawnUi.dpi(this, 24);
        root.addView(status, lp);
        setContentView(root);

        AirPlayReceiver.get().setListener(this);
        AirPlayReceiver.get().setVideoLayout(this, videoLayout);
        startReceiver();
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

    @Override public void onConnected() { runOnUiThread(() -> status.setText(getString(R.string.airplay_connected))); }
    @Override public void onDisconnected() { runOnUiThread(() -> status.setText(getString(R.string.airplay_waiting))); }

    @Override public void onBackPressed() {
        AirPlayReceiver.get().stop();
        finish();
    }
}
