package com.gathin.tvhome;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * AirPlay mirror receiver for Android. The AirPlay protocol (pairing, FairPlay, RTP) is handled by
 * the bundled open-source UxPlay engine (GPL) in libairplay_jni.so; this class decodes the H.264/
 * H.265 video it hands us with the TV's hardware MediaCodec and renders to a Surface. Requires
 * Android 5.0+ (MediaCodec async/low-latency) — on Android 4.4 the engine still runs but mirroring
 * may not decode; the DLNA receiver is the 4.4 path.
 */
public final class AirPlayReceiver {
    static { try { System.loadLibrary("airplay_jni"); } catch (Throwable t) { Log.e("AirPlay", "lib load failed", t); } }

    private static final String TAG = "AirPlay";
    private static final String MIME_H264 = "video/avc";
    private static final String MIME_H265 = "video/hevc";

    private static AirPlayReceiver sInstance;
    private MediaCodec videoCodec;
    private MediaCodec audioCodec;
    private Surface surface;
    private boolean isH265;
    private byte[] sps, pps, vps;
    private volatile boolean started;

    public interface Listener { void onConnected(); void onDisconnected(); }
    private Listener listener;
    public void setListener(Listener l) { listener = l; }

    public static synchronized AirPlayReceiver get() {
        if (sInstance == null) sInstance = new AirPlayReceiver();
        return sInstance;
    }

    public boolean isStarted() { return started; }

    public boolean start(Context ctx, String name, int width, int height) {
        if (started) return true;
        Log.i(TAG, "AirPlay start on API " + Build.VERSION.SDK_INT);
        String mac = "AA:BB:CC:DD:EE:FF";
        String keyfile = new java.io.File(ctx.getFilesDir(), "airplay.key").getAbsolutePath();
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
        releaseVideo();
        releaseAudio();
        started = false;
    }

    public void setSurface(Surface s) {
        this.surface = s;
        if (videoCodec != null && s != null && Build.VERSION.SDK_INT >= 21) {
            try { videoCodec.setInputSurface(s); } catch (Throwable ignored) { }
        }
    }

    // ---- called from native ----
    private void onVideo(int h265, byte[] data) {
        if (data == null || data.length == 0) return;
        if (surface == null) return;   // wait until the playback surface is ready
        boolean wantH265 = h265 != 0;
        if (videoCodec == null || wantH265 != isH265) initVideo(wantH265);
        if (videoCodec == null) return;
        feed(videoCodec, data);
    }

    private void onAudio(byte[] data) {
        if (data == null || data.length == 0) return;
        if (audioCodec == null) initAudio();
        if (audioCodec == null) return;
        feed(audioCodec, data);
    }

    private void onConnection(boolean connected) {
        Log.i(TAG, "connection " + connected);
        final Listener l = listener;
        if (connected) { if (l != null) l.onConnected(); }
        else { releaseVideo(); releaseAudio(); if (l != null) l.onDisconnected(); }
    }

    private void onMirrorRunning(boolean running) {
        Log.i(TAG, "mirror running " + running);
    }

    private synchronized void initVideo(boolean h265) {
        releaseVideo();
        try {
            String mime = h265 ? MIME_H265 : MIME_H264;
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, 1920, 1080);
            videoCodec = MediaCodec.createDecoderByType(mime);
            videoCodec.configure(fmt, surface, null, 0);
            videoCodec.start();
            isH265 = h265;
            Log.i(TAG, "video decoder started " + mime);
        } catch (Throwable t) {
            Log.e(TAG, "initVideo failed", t);
            videoCodec = null;
        }
    }

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

    private void feed(MediaCodec codec, byte[] data) {
        try {
            int inIndex = codec.dequeueInputBuffer(8000);
            if (inIndex >= 0) {
                ByteBuffer buf;
                if (Build.VERSION.SDK_INT >= 21) {
                    buf = codec.getInputBuffer(inIndex);
                } else {
                    buf = codec.getInputBuffers()[inIndex];   // API19 path
                }
                if (buf != null) {
                    buf.clear();
                    buf.put(data);
                    codec.queueInputBuffer(inIndex, 0, data.length, System.nanoTime() / 1000, 0);
                }
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIndex = codec.dequeueOutputBuffer(info, 0);
            while (outIndex >= 0) {
                boolean render = (info.size != 0) && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                codec.releaseOutputBuffer(outIndex, render);
                outIndex = codec.dequeueOutputBuffer(info, 0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "feed error", t);
        }
    }

    private void releaseVideo() {
        if (videoCodec != null) {
            try { videoCodec.stop(); } catch (Throwable ignored) { }
            try { videoCodec.release(); } catch (Throwable ignored) { }
            videoCodec = null;
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
