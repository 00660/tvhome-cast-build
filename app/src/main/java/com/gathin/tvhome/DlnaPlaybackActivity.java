package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.videolan.libvlc.util.VLCVideoLayout;

/** Full-screen renderer for media pushed over DLNA. Uses libVLC (FFmpeg) + VLCVideoLayout for
 *  software decoding + full-screen video of high-bitrate FLV/HLS/MP4. Back returns to desktop. */
public final class DlnaPlaybackActivity extends Activity {
    private VLCVideoLayout videoLayout;
    private SelfDrawnUi.TextLine hint;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(-1, -1));

        hint = new SelfDrawnUi.TextLine(this, getString(R.string.dlna_casting), SelfDrawnUi.TextLine.SECTION);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = SelfDrawnUi.dpi(this, 24);
        root.addView(hint, hp);
        hint.setAlpha(0.85f);

        setContentView(root);

        DlnaPlayer.get(this).setListener(this::refreshHint);
        DlnaPlayer.get(this).setVideoLayout(videoLayout);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("uri")) {
            DlnaPlayer.get(this).setUri(intent.getStringExtra("uri"));
        }
    }

    private void refreshHint() {
        if (hint == null) return;
        String s = DlnaPlayer.get(this).getState();
        hint.setText(getString("PLAYING".equals(s) ? R.string.dlna_casting
                : "TRANSITIONING".equals(s) ? R.string.dlna_loading : R.string.dlna_casting));
    }

    @Override public void onBackPressed() {
        DlnaPlayer.get(this).stop();
        DlnaPlayer.get(this).release();
        finish();
    }
}
