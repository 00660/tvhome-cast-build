package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * Auto-shown full-screen surface for AirPlay mirroring. Video is decoded by libVLC (FFmpeg) into a
 * VLCVideoLayout (see AirPlayReceiver/AirPlayVideoSink). Auto-launched on connect; closes on disconnect.
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
    }

    @Override public void onConnected() { runOnUiThread(() -> status.setText(getString(R.string.airplay_connected))); }
    @Override public void onDisconnected() { runOnUiThread(this::finish); }
    @Override protected void onDestroy() { AirPlayReceiver.get().clearVideoLayout(); super.onDestroy(); }
    @Override public void onBackPressed() { finish(); }
}
