package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/** Auto-shown full-screen AirPlay mirror surface. Native FFmpeg soft-decodes the H.264/HEVC from the
 *  UxPlay engine straight onto a SurfaceView (see AirPlayReceiver/NativePlayer). No overlay text. */
public final class AirPlayPlaybackActivity extends Activity implements AirPlayReceiver.Listener, SurfaceHolder.Callback {
    private SurfaceView surfaceView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        AirPlayReceiver.get().setListener(this);
    }
    @Override public void surfaceCreated(SurfaceHolder h){ AirPlayReceiver.get().setSurfaceView(this, surfaceView); }
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int ht){ AirPlayReceiver.get().setSurfaceView(this, surfaceView); }
    @Override public void surfaceDestroyed(SurfaceHolder h){ AirPlayReceiver.get().clearSurface(); }
    @Override public void onConnected() { }
    @Override public void onDisconnected() { runOnUiThread(this::finish); }
    @Override protected void onDestroy() { AirPlayReceiver.get().clearSurface(); super.onDestroy(); }
    @Override public void onBackPressed() { finish(); }
}
