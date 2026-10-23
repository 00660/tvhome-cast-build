package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * AirPlay mirror receiver. UxPlay (bundled C, GPL) handles the AirPlay protocol and hands us bare
 * H.264 Annex-B video (with SPS/PPS prepended) and AAC audio. Video is decoded DIRECTLY by the TV's
 * hardware MediaCodec straight onto the SurfaceView (no TCP relay, no libVLC); audio by MediaCodec.
 * Works on Android 4.4 (MediaCodec is API16+).
 */
public final class AirPlayReceiver {
    static { try { System.loadLibrary("airplay_jni"); } catch (Throwable t) { Log.e("AirPlay", "lib load failed", t); } }

    private static final String TAG = "AirPlay";
    private static AirPlayReceiver sInstance;

    private Context app;
    private volatile boolean started;
    private MediaCodec videoCodec;
    private MediaCodec audioCodec;
    private Surface surface;
    private volatile boolean videoConfigured;
    private boolean isH265;
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
    public boolean hasVideo() { return surface != null; }

    /** Called by AirPlayPlaybackActivity to give us the decode target + start decoding. */
    public void setSurface(Context ctx, Surface s) {
        this.app = ctx.getApplicationContext();
        this.surface = s;
        // (Re)create the video decoder bound to this surface on first real video.
        if (videoCodec != null) { releaseVideo(); }
        videoConfigured = false;
    }
    public void clearSurface() { surface = null; releaseVideo(); }

    public boolean start(Context ctx, String name, int width, int height) {
        if (started) return true;
        app = ctx.getApplicationContext();
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("airplay-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
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
        releaseVideo(); releaseAudio();
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Throwable ignored) { }
        multicastLock = null; started = false;
    }

    // ---- called from native (UxPlay) ----
    private void onVideo(int h265, byte[] data) {
        if (data == null || data.length == 0) return;
        if (surface == null) return;   // wait until the mirror activity gave us a surface
        boolean wantH265 = h265 != 0;
        if (videoCodec == null || wantH265 != isH265 || !videoConfigured) {
            if (!initVideo(wantH265)) return;
        }
        feedVideo(data);
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
            releaseVideo(); releaseAudio();
            if (l != null) l.onDisconnected();
        }
    }
    private void onMirrorRunning(boolean running) { Log.i(TAG, "mirror running " + running); }

    private synchronized boolean initVideo(boolean h265) {
        releaseVideo();
        try {
            String mime = h265 ? "video/hevc" : "video/avc";
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, 1920, 1080);
            // low latency helps mirroring; ignored on old decoders
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            videoCodec = MediaCodec.createDecoderByType(mime);
            videoCodec.configure(fmt, surface, null, 0);
            videoCodec.start();
            isH265 = h265; videoConfigured = true;
            Log.i(TAG, "video decoder started " + mime);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "initVideo failed", t);
            // retry without low-latency key (some 4.4 decoders reject unknown keys)
            try {
                String mime = h265 ? "video/hevc" : "video/avc";
                MediaFormat fmt = MediaFormat.createVideoFormat(mime, 1920, 1080);
                videoCodec = MediaCodec.createDecoderByType(mime);
                videoCodec.configure(fmt, surface, null, 0);
                videoCodec.start();
                isH265 = h265; videoConfigured = true;
                Log.i(TAG, "video decoder started (2) " + mime);
                return true;
            } catch (Throwable t2) { Log.e(TAG, "initVideo failed 2", t2); videoCodec = null; return false; }
        }
    }

    private void feedVideo(byte[] data) {
        try {
            int inIndex = videoCodec.dequeueInputBuffer(8000);
            if (inIndex >= 0) {
                ByteBuffer buf = android.os.Build.VERSION.SDK_INT >= 21 ? videoCodec.getInputBuffer(inIndex) : videoCodec.getInputBuffers()[inIndex];
                if (buf != null) { buf.clear(); buf.put(data); videoCodec.queueInputBuffer(inIndex, 0, data.length, System.nanoTime() / 1000, 0); }
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIndex;
            while ((outIndex = videoCodec.dequeueOutputBuffer(info, 0)) >= 0) {
                boolean render = info.size != 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                videoCodec.releaseOutputBuffer(outIndex, render);
            }
        } catch (Throwable t) { Log.e(TAG, "video feed", t); }
    }

    private synchronized void initAudio() {
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            audioCodec = MediaCodec.createDecoderByType("audio/mp4a-latm");
            audioCodec.configure(fmt, null, null, 0);
            audioCodec.start();
        } catch (Throwable t) { Log.e(TAG, "initAudio failed", t); audioCodec = null; }
    }

    private void releaseVideo() {
        if (videoCodec != null) { try { videoCodec.stop(); } catch (Throwable ignored) { } try { videoCodec.release(); } catch (Throwable ignored) { } videoCodec = null; }
        videoConfigured = false;
    }
    private void releaseAudio() {
        if (audioCodec != null) { try { audioCodec.stop(); } catch (Throwable ignored) { } try { audioCodec.release(); } catch (Throwable ignored) { } audioCodec = null; }
    }

    private native boolean nativeStart(String name, String mac, String keyfile, int width, int height);
    private native void nativeStop();
}
