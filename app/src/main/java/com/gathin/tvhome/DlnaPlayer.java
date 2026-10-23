package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

/**
 * DLNA playback engine using the native FFmpeg soft-decoder (NativePlayer). Plays any URL (FLV/HLS/
 * MP4/video) by software decode onto a SurfaceView — no libVLC, no MediaCodec, handles high bitrate.
 */
final class DlnaPlayer {
    interface Listener { void onStateChanged(); }
    static final String STOPPED="STOPPED", PLAYING="PLAYING", PAUSED="PAUSED_PLAYBACK", TRANSITIONING="TRANSITIONING", NO_MEDIA="NO_MEDIA_PRESENT";

    private static DlnaPlayer instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context app;
    private Surface surface;
    private String uri;
    private String state = NO_MEDIA;
    private Listener listener;

    private DlnaPlayer(Context c){ this.app=c.getApplicationContext(); }
    static synchronized DlnaPlayer get(Context c){ if(instance==null) instance=new DlnaPlayer(c); return instance; }
    static synchronized DlnaPlayer get(){ return instance; }

    void setListener(Listener l){ listener=l; }
    String getState(){ return state; }
    String getUri(){ return uri; }
    private void notifyChanged(){ if(listener!=null) listener.onStateChanged(); }

    synchronized void setSurface(Surface s){
        surface=s;
        main.post(() -> { if(uri!=null && surface!=null) startPlayback(); });
    }
    synchronized void clearSurface(){ surface=null; main.post(NativePlayer::stopUrl); }

    synchronized void setUri(final String newUri){
        uri=newUri;
        main.post(() -> {
            NativePlayer.stopUrl();
            state = newUri==null?NO_MEDIA:STOPPED;
            notifyChanged();
            if(newUri!=null && surface!=null) startPlayback();
        });
    }
    private void startPlayback(){
        if(uri==null||surface==null) return;
        state=TRANSITIONING; notifyChanged();
        NativePlayer.playUrl(uri, surface, false);
        state=PLAYING; notifyChanged();
        Log.i("DLNA","ffmpeg play "+uri);
    }
    synchronized void play(){ main.post(() -> { if(uri!=null && surface!=null) startPlayback(); }); }
    synchronized void pause(){ main.post(NativePlayer::stopUrl); state=PAUSED; notifyChanged(); }
    synchronized void stop(){ main.post(NativePlayer::stopUrl); state=STOPPED; notifyChanged(); }
    synchronized void seek(int ms){ /* FFmpeg URL player: seek not wired; ignore */ }
    int position(){ return 0; }
    int duration(){ return 0; }

    synchronized void release(){ main.post(NativePlayer::stopUrl); state=NO_MEDIA; uri=null; notifyChanged(); }
}
