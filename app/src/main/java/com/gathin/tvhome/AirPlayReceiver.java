package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
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
    private final Object audioLock = new Object();
    private MediaCodec audioCodec;
    private AudioTrack audioTrack;
    private int audioSampleRate = 44100;
    private int audioChannelCount = 2;
    private long nextAudioPtsUs;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable audioIdleStop = () -> releaseAudio();

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

    public synchronized boolean start(Context ctx, String name, int width, int height) {
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
        if (!started) {
            try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Throwable ignored) { }
            multicastLock = null;
        }
        return started;
    }

    public synchronized void stop() {
        try { nativeStop(); } catch (Throwable ignored) { }
        main.removeCallbacks(audioIdleStop);
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
        main.removeCallbacks(audioIdleStop);
        main.postDelayed(audioIdleStop, 2000);
        synchronized (audioLock) {
            if (audioCodec == null) initAudio();
            if (audioCodec == null) return;
            try {
                int inIndex = audioCodec.dequeueInputBuffer(8000);
                if (inIndex >= 0) {
                    ByteBuffer buf = android.os.Build.VERSION.SDK_INT >= 21 ? audioCodec.getInputBuffer(inIndex) : audioCodec.getInputBuffers()[inIndex];
                    if (buf != null && data.length <= buf.capacity()) {
                        buf.clear();
                        buf.put(data);
                        long ptsUs = nextAudioPtsUs;
                        nextAudioPtsUs += 1024_000_000L / audioSampleRate;
                        audioCodec.queueInputBuffer(inIndex, 0, data.length, ptsUs, 0);
                    } else {
                        audioCodec.queueInputBuffer(inIndex, 0, 0, nextAudioPtsUs, 0);
                        Log.w(TAG, "dropping oversized AAC frame: " + data.length);
                    }
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIndex;
                while ((outIndex = audioCodec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outIndex >= 0) {
                        try {
                            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                ByteBuffer pcm = android.os.Build.VERSION.SDK_INT >= 21 ? audioCodec.getOutputBuffer(outIndex) : audioCodec.getOutputBuffers()[outIndex];
                                if (pcm != null) writePcm(pcm, info);
                            }
                        } finally {
                            audioCodec.releaseOutputBuffer(outIndex, false);
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        configureAudioTrack(audioCodec.getOutputFormat());
                    } else {
                        break;
                    }
                }
            } catch (Throwable t) { Log.e(TAG, "audio feed", t); }
        }
    }

    private void writePcm(ByteBuffer pcm, MediaCodec.BufferInfo info) {
        configureAudioTrack(audioCodec.getOutputFormat());
        if (audioTrack == null || info.size <= 0) return;
        pcm.position(info.offset);
        pcm.limit(info.offset + info.size);
        byte[] bytes = new byte[info.size];
        pcm.get(bytes);
        int offset = 0;
        while (offset < bytes.length) {
            int written = audioTrack.write(bytes, offset, bytes.length - offset);
            if (written <= 0) {
                Log.w(TAG, "AudioTrack write failed: " + written);
                break;
            }
            offset += written;
        }
    }

    private void configureAudioTrack(MediaFormat format) {
        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        if (channels != 1 && channels != 2) channels = 2;
        if (audioTrack != null && sampleRate == audioSampleRate && channels == audioChannelCount) return;
        releaseAudioTrack();
        int channelConfig = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBytes = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            Log.e(TAG, "unsupported PCM output format: " + sampleRate + " Hz, " + channels + " channels");
            return;
        }
        try {
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBytes, sampleRate * channels / 2),
                    AudioTrack.MODE_STREAM);
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                releaseAudioTrack();
                return;
            }
            audioSampleRate = sampleRate;
            audioChannelCount = channels;
            audioTrack.play();
        } catch (Throwable t) {
            Log.e(TAG, "AudioTrack init failed", t);
            releaseAudioTrack();
        }
    }

    private void releaseAudioTrack() {
        if (audioTrack == null) return;
        try { audioTrack.pause(); } catch (Throwable ignored) { }
        try { audioTrack.flush(); } catch (Throwable ignored) { }
        try { audioTrack.release(); } catch (Throwable ignored) { }
        audioTrack = null;
    }

    private void onConnection(boolean connected) {
        Log.i(TAG, "connection " + connected);
    }

    private void onMirrorRunning(boolean running) {
        Log.i(TAG, "mirror running " + running);
        final Listener l = listener;
        if (running) {
            if (app != null) main.post(() -> {
                try {
                    Intent i = new Intent(app, AirPlayPlaybackActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    app.startActivity(i);
                } catch (Throwable ignored) { }
            });
            if (l != null) l.onConnected();
        } else {
            main.removeCallbacks(audioIdleStop);
            NativePlayer.releaseRaw();
            releaseAudio();
            if (l != null) l.onDisconnected();
        }
    }



    private void initAudio() {
        MediaCodec codec = null;
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            codec = MediaCodec.createDecoderByType("audio/mp4a-latm");
            codec.configure(fmt, null, null, 0);
            codec.start();
            audioCodec = codec;
        } catch (Throwable t) {
            Log.e(TAG, "initAudio failed", t);
            if (codec != null) try { codec.release(); } catch (Throwable ignored) { }
            audioCodec = null;
        }
    }
    private void releaseAudio() {
        synchronized (audioLock) {
            if (audioCodec != null) { try { audioCodec.stop(); } catch (Throwable ignored) { } try { audioCodec.release(); } catch (Throwable ignored) { } audioCodec = null; }
            releaseAudioTrack();
            nextAudioPtsUs = 0;
        }
    }

    private native boolean nativeStart(String name, String mac, String keyfile, int width, int height);
    private native void nativeStop();
}
