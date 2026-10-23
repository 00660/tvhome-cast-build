package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/** Full-screen DLNA renderer using the native FFmpeg soft-decoder onto a SurfaceView. */
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
        hint.setAlpha(0f); // no overlay text over video
        setContentView(root);
        DlnaPlayer.get(this).setListener(this::refreshHint);
        handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){ super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    private void handleIntent(Intent intent){ if(intent!=null && intent.hasExtra("uri")) DlnaPlayer.get(this).setUri(intent.getStringExtra("uri")); }
    private void refreshHint(){ /* overlay hidden */ }
    @Override public void surfaceCreated(SurfaceHolder h){ DlnaPlayer.get(this).setSurface(h.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int ht){ DlnaPlayer.get(this).setSurface(h.getSurface()); }
    @Override public void surfaceDestroyed(SurfaceHolder h){ DlnaPlayer.get(this).clearSurface(); }
    @Override public void onBackPressed(){ DlnaPlayer.get(this).stop(); DlnaPlayer.get(this).release(); finish(); }
}
