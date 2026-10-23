package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import org.videolan.libvlc.util.VLCVideoLayout;

import java.nio.ByteBuffer;

/**
 * AirPlay mirror receiver. The AirPlay protocol (pairing, FairPlay, RTP) is handled by the bundled
 * open-source UxPlay engine (GPL) in libairplay_jni.so. It hands us bare H.264 Annex-B video and AAC
 * audio. Video is decoded in software by libVLC (AirPlayVideoSink) so high-bitrate mirroring works on
 * this TV's weak hardware; audio by MediaCodec. Works on Android 4.4.
 *
 * Standalone (no Activity): the Settings switch starts/stops it. VideoSink starts when a video
 * layout is attached by AirPlayPlaybackActivity (which is auto-launched when a client mirrors).
 */
public final class AirPlayReceiver {
    static { try { System.loadLibrary("airplay_jni"); } catch (Throwable t) { Log.e("AirPlay", "lib load failed", t); } }

    private static final String TAG = "AirPlay";
    private static AirPlayReceiver sInstance;

    private MediaCodec audioCodec;
    private AirPlayVideoSink videoSink;
    private Context app;
    private VLCVideoLayout layout;
    private volatile boolean started;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Fired from the native thread when a client connects/disconnects. */
    public interface Listener { void onConnected(); void onDisconnected(); }
    private volatile Listener listener;
    public void setListener(Listener l) { listener = l; }

    public static synchronized AirPlayReceiver get() {
        if (sInstance == null) sInstance = new AirPlayReceiver();
        return sInstance;
    }

    public boolean isStarted() { return started; }

    /** Called by AirPlayPlaybackActivity when it is shown (on connect) to attach the video surface. */
    public void setVideoLayout(VLCVideoLayout l) {
        this.layout = l;
        if (videoSink == null && app != null) videoSink = new AirPlayVideoSink(app);
        if (videoSink != null) videoSink.setLayout(l);
    }

    public void clearVideoLayout() {
        this.layout = null;
    }

    /** Start the AirPlay server (called by the Settings switch / boot). */
    public boolean start(Context ctx, String name, int width, int height) {
        if (started) return true;
        app = ctx.getApplicationContext();
        Log.i(TAG, "AirPlay start");
        String mac = "AA:BB:CC:DD:EE:FF";
        String keyfile = new java.io.File(app.getFilesDir(), "airplay.key").getAbsolutePath();
        try {
            boolean ok = nativeStart(name, mac, keyfile, width, height);
            started = ok;
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "nativeStart failed", t);
            return false;
        }
    }

    public void stop() {
        try { nativeStop(); } catch (Throwable ignored) { }
        releaseAudio();
        if (videoSink != null) { videoSink.stop(); videoSink = null; }
        started = false;
    }

    // ---- called from native ----
    private void onVideo(int h265, byte[] data) {
        if (data == null || data.length == 0) return;
        // Lazily start the video sink the first time video arrives (layout attached by the activity).
        if (videoSink == null && app != null) { videoSink = new AirPlayVideoSink(app); videoSink.setLayout(layout); videoSink.start(); }
        if (videoSink != null) videoSink.write(data);
    }

    private void onAudio(byte[] data) {
        if (data == null || data.length == 0) return;
        if (audioCodec == null) initAudio();
        if (audioCodec == null) return;
        feedAudio(data);
    }

    private void onConnection(boolean connected) {
        Log.i(TAG, "connection " + connected);
        final Listener l = listener;
        if (connected) {
            // Auto-show the full-screen mirror surface when a client starts streaming.
            if (app != null) {
                main.post(() -> {
                    try {
                        Intent i = new Intent(app, AirPlayPlaybackActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        app.startActivity(i);
                    } catch (Throwable ignored) { }
                });
            }
            if (l != null) l.onConnected();
        } else { releaseAudio(); if (l != null) l.onDisconnected(); }
    }

    private void onMirrorRunning(boolean running) { Log.i(TAG, "mirror running " + running); }

    private synchronized void initAudio() {
        releaseAudio();
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            audioCodec = MediaCodec.createDecoderByType("audio/mp4a-latm");
            audioCodec.configure(fmt, null, null, 0);
            audioCodec.start();
            Log.i(TAG, "audio decoder started");
        } catch (Throwable t) {
            Log.e(TAG, "initAudio failed", t);
            audioCodec = null;
        }
    }

    private void feedAudio(byte[] data) {
        try {
            int inIndex = audioCodec.dequeueInputBuffer(8000);
            if (inIndex >= 0) {
                ByteBuffer buf;
                if (android.os.Build.VERSION.SDK_INT >= 21) buf = audioCodec.getInputBuffer(inIndex);
                else buf = audioCodec.getInputBuffers()[inIndex];
                if (buf != null) {
                    buf.clear();
                    buf.put(data);
                    audioCodec.queueInputBuffer(inIndex, 0, data.length, System.nanoTime() / 1000, 0);
                }
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIndex = audioCodec.dequeueOutputBuffer(info, 0);
            while (outIndex >= 0) {
                boolean render = (info.size != 0) && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                audioCodec.releaseOutputBuffer(outIndex, render);
                outIndex = audioCodec.dequeueOutputBuffer(info, 0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "feedAudio error", t);
        }
    }

    private void releaseAudio() {
        if (audioCodec != null) {
            try { audioCodec.stop(); } catch (Throwable ignored) { }
            try { audioCodec.release(); } catch (Throwable ignored) { }
            audioCodec = null;
        }
    }

    private native boolean nativeStart(String name, String mac, String keyfile, int width, int height);
    private native void nativeStop();
}
