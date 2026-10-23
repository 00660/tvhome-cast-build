package com.gathin.tvhome;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.Surface;
import android.view.SurfaceView;

/**
 * Thin bridge to the native FFmpeg soft-decode player (libavformat/libavcodec/libswscale → ANativeWindow).
 * Used by both DLNA (URL playback) and AirPlay (raw H.264/HEVC from the UxPlay engine). Software decode
 * so modern high-bitrate streams play on this Android 4.4 TV's weak hardware codec. No libVLC used.
 */
public final class NativePlayer {
    static { try { System.loadLibrary("airplay_jni"); } catch (Throwable ignored) { } }

    private static final Object AUDIO_LOCK = new Object();
    private static AudioTrack audioTrack;
    private static int audioSampleRate;
    private static int audioChannels;

    private NativePlayer() { }

    // DLNA: play a URL onto a Surface.
    public static void playUrl(String url, Surface surface, boolean rotate90) {
        nativePlayUrl(url, surface, rotate90 ? 90 : 0);
    }
    public static void stopUrl() {
        nativeStopUrl();
        synchronized (AUDIO_LOCK) {
            if (audioTrack != null) {
                try { audioTrack.pause(); } catch (Throwable ignored) { }
                try { audioTrack.flush(); } catch (Throwable ignored) { }
                try { audioTrack.release(); } catch (Throwable ignored) { }
                audioTrack = null;
            }
            audioSampleRate = 0;
            audioChannels = 0;
        }
    }

    /** Called by the native FFmpeg URL decoder with interleaved signed 16-bit PCM. */
    private static void onAudioPcm(byte[] pcm, int sampleRate, int channels) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) return;
        channels = channels == 1 ? 1 : 2;
        synchronized (AUDIO_LOCK) {
            if (audioTrack == null || sampleRate != audioSampleRate || channels != audioChannels) {
                if (audioTrack != null) {
                    try { audioTrack.pause(); } catch (Throwable ignored) { }
                    try { audioTrack.flush(); } catch (Throwable ignored) { }
                    try { audioTrack.release(); } catch (Throwable ignored) { }
                    audioTrack = null;
                }
                int channelConfig = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                int minBytes = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
                if (minBytes <= 0) return;
                try {
                    audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(minBytes * 2, sampleRate * channels),
                            AudioTrack.MODE_STREAM);
                    if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                        audioTrack.release();
                        audioTrack = null;
                        return;
                    }
                    audioSampleRate = sampleRate;
                    audioChannels = channels;
                    audioTrack.play();
                } catch (Throwable t) {
                    audioTrack = null;
                    android.util.Log.e("DLNA", "AudioTrack init failed", t);
                    return;
                }
            }
            int offset = 0;
            while (offset < pcm.length) {
                int written = audioTrack.write(pcm, offset, pcm.length - offset);
                if (written <= 0) {
                    android.util.Log.w("DLNA", "AudioTrack write failed: " + written);
                    break;
                }
                offset += written;
            }
        }
    }

    // AirPlay: raw H.264/HEVC NAL units decoded onto a Surface.
    public static void setRawSurface(SurfaceView sv, boolean rotate90) {
        Surface s = sv != null && sv.getHolder() != null ? sv.getHolder().getSurface() : null;
        nativeSetRawSurface(s, rotate90 ? 90 : 0);
    }
    public static void setRawSurface(Surface s, boolean rotate90) { nativeSetRawSurface(s, rotate90 ? 90 : 0); }
    public static void feedRaw(boolean isH265, byte[] nal) { nativeFeedRaw(isH265 ? 1 : 0, nal); }
    public static void releaseRaw() { nativeReleaseRaw(); }

    private static native boolean nativePlayUrl(String url, Surface surface, int rotate);
    private static native void nativeStopUrl();
    private static native void nativeSetRawSurface(Surface surface, int rotate);
    private static native void nativeFeedRaw(int isH265, byte[] data);
    private static native void nativeReleaseRaw();
}
