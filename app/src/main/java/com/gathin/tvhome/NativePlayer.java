package com.gathin.tvhome;

import android.view.Surface;
import android.view.SurfaceView;

/**
 * Thin bridge to the native FFmpeg soft-decode player (libavformat/libavcodec/libswscale → ANativeWindow).
 * Used by both DLNA (URL playback) and AirPlay (raw H.264/HEVC from the UxPlay engine). Software decode
 * so modern high-bitrate streams play on this Android 4.4 TV's weak hardware codec. No libVLC used.
 */
public final class NativePlayer {
    static { try { System.loadLibrary("airplay_jni"); } catch (Throwable ignored) { } }

    private NativePlayer() { }

    // DLNA: play a URL onto a Surface.
    public static void playUrl(String url, Surface surface, boolean rotate90) {
        nativePlayUrl(url, surface, rotate90 ? 90 : 0);
    }
    public static void stopUrl() { nativeStopUrl(); }

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
