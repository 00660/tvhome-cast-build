package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;

import java.nio.ByteBuffer;

/**
 * AirPlay mirror receiver. UxPlay (bundled C, GPL) handles the AirPlay protocol and hands us bare
 * H.264 Annex-B video (SPS/PPS already prepended) + AAC audio. Video is decoded by the TV's hardware
 * MediaCodec straight onto a SurfaceView (standard Android mirroring path — no pipe, no TCP, no
 * libVLC). Audio by MediaCodec. Works on Android 4.4 (MediaCodec is API16+, uses buffer-array API).
 */
public final class AirPlayReceiver {
    static { try { System.loadLibrary("airplay_jni"); } catch (Throwable t) { Log.e("AirPlay", "lib load failed", t); } }

    private static final String TAG = "AirPlay";
    private static AirPlayReceiver sInstance;

    private Context app;
    private volatile boolean started;
    private volatile boolean rotate = true;
    private MediaCodec audioCodec;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    public interface Listener { void onConnected(); void onDisconnected(); }
    private volatile Listener listener;
    public void setListener(Listener l) { listener = l; }

    public static synchronized AirPlayReceiver get() {
        if (sInstance == null) sInstance = new AirPlayReceiver();
        return sInstance;
    }

    public boolean isStarted() { return started; }

    /** Called by the mirror Activity with the SurfaceView for the native FFmpeg raw decoder. */
    public void setSurfaceView(Context ctx, SurfaceView sv) {
        this.app = ctx.getApplicationContext();
        NativePlayer.setRawSurface(sv, rotate);
        Log.i(TAG, "raw surface set");
    }
    public void clearSurface() { NativePlayer.releaseRaw(); }

    public boolean start(Context ctx, String name, int width, int height) {
        if (started) return true;
        app = ctx.getApplicationContext();
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("airplay-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                Log.i(TAG, "multicast lock acquired");
            }
        } catch (Throwable t) { Log.e(TAG, "multicast lock failed", t); }
        String mac = "AA:BB:CC:DD:EE:FF";
        String keyfile = new java.io.File(app.getFilesDir(), "airplay.key").getAbsolutePath();
        try { started = nativeStart(name, mac, keyfile, width, height); }
        catch (Throwable t) { Log.e(TAG, "nativeStart failed", t); started = false; }
        return started;
    }

    public void stop() {
        try { nativeStop(); } catch (Throwable ignored) { }
        NativePlayer.releaseRaw(); releaseAudio();
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Throwable ignored) { }
        multicastLock = null; started = false;
    }

    // ---- called from native (UxPlay) ----
    private void onVideo(int h265, byte[] data) {
        if (data == null || data.length == 0) return;
        NativePlayer.feedRaw(h265 != 0, data);   // native FFmpeg soft-decode → ANativeWindow
    }

    private void onAudio(byte[] data) {
        if (data == null || data.length == 0) return;
        if (audioCodec == null) initAudio();
        if (audioCodec == null) return;
        try {
            int inIndex = audioCodec.dequeueInputBuffer(8000);
            if (inIndex >= 0) {
                ByteBuffer buf = android.os.Build.VERSION.SDK_INT >= 21 ? audioCodec.getInputBuffer(inIndex) : audioCodec.getInputBuffers()[inIndex];
                if (buf != null) { buf.clear(); buf.put(data); audioCodec.queueInputBuffer(inIndex, 0, data.length, System.nanoTime() / 1000, 0); }
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIndex;
            while ((outIndex = audioCodec.dequeueOutputBuffer(info, 0)) >= 0) audioCodec.releaseOutputBuffer(outIndex, true);
        } catch (Throwable t) { Log.e(TAG, "audio feed", t); }
    }

    private void onConnection(boolean connected) {
        Log.i(TAG, "connection " + connected);
        final Listener l = listener;
        if (connected) {
            if (app != null) main.post(() -> {
                try {
                    Intent i = new Intent(app, AirPlayPlaybackActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    app.startActivity(i);
                } catch (Throwable ignored) { }
            });
            if (l != null) l.onConnected();
        } else {
            NativePlayer.releaseRaw(); releaseAudio();
            if (l != null) l.onDisconnected();
        }
    }
    private void onMirrorRunning(boolean running) { Log.i(TAG, "mirror running " + running); }



    private synchronized void initAudio() {
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            audioCodec = MediaCodec.createDecoderByType("audio/mp4a-latm");
            audioCodec.configure(fmt, null, null, 0);
            audioCodec.start();
        } catch (Throwable t) { Log.e(TAG, "initAudio failed", t); audioCodec = null; }
    }
    private void releaseAudio() {
        if (audioCodec != null) { try { audioCodec.stop(); } catch (Throwable ignored) { } try { audioCodec.release(); } catch (Throwable ignored) { } audioCodec = null; }
    }

    private native boolean nativeStart(String name, String mac, String keyfile, int width, int height);
    private native void nativeStop();
}
