package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/** Full-screen renderer for media pushed over DLNA. Uses libVLC (FFmpeg) for software decoding of
 *  high-bitrate FLV/HLS/MP4 that this TV's hardware codec cannot handle. Back returns to desktop. */
public final class DlnaPlaybackActivity extends Activity {
    private SurfaceView surfaceView;
    private SelfDrawnUi.TextLine hint;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        hint = new SelfDrawnUi.TextLine(this, getString(R.string.dlna_casting), SelfDrawnUi.TextLine.SECTION);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = SelfDrawnUi.dpi(this, 24);
        root.addView(hint, hp);
        hint.setAlpha(0.85f);

        setContentView(root);

        DlnaPlayer.get(this).setListener(this::refreshHint);
        DlnaPlayer.get(this).setSurfaceView(surfaceView);
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
