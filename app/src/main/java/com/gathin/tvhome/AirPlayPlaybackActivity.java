package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * Auto-shown full-screen surface for AirPlay mirroring. It is NOT opened by a menu; the HomeKit/
 * Settings AirPlay switch starts the receiver in the background, and when an iPhone starts mirroring
 * the launcher auto-launches this activity to attach the VLC surface. When the client disconnects it
 * closes itself. Back also closes it.
 */
public final class AirPlayPlaybackActivity extends Activity implements AirPlayReceiver.Listener {
    private VLCVideoLayout videoLayout;
    private SelfDrawnUi.TextLine status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(-1, -1));
        status = new SelfDrawnUi.TextLine(this, getString(R.string.airplay_connected), SelfDrawnUi.TextLine.SECTION);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = SelfDrawnUi.dpi(this, 24);
        root.addView(status, lp);
        setContentView(root);

        AirPlayReceiver.get().setListener(this);
        AirPlayReceiver.get().setVideoLayout(videoLayout);
        // Make sure the receiver is running (in case it was started by boot and not yet on).
        if (!AirPlayReceiver.get().isStarted()) {
            new Thread(() -> AirPlayReceiver.get().start(this, getString(R.string.airplay_name), 1920, 1080), "airplay-start").start();
        }
    }

    @Override public void onConnected() { runOnUiThread(() -> status.setText(getString(R.string.airplay_connected))); }
    @Override public void onDisconnected() {
        runOnUiThread(() -> {
            AirPlayReceiver.get().clearVideoLayout();
            finish();
        });
    }

    @Override protected void onDestroy() {
        AirPlayReceiver.get().clearVideoLayout();
        super.onDestroy();
    }

    @Override public void onBackPressed() { finish(); }
}
