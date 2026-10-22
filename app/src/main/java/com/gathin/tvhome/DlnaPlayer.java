package com.gathin.tvhome;

import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * Shared DLNA playback engine: one MediaPlayer, driven by the DLNA renderer (SOAP actions) and
 * rendered onto the surface provided by {@link DlnaPlaybackActivity}. Kept out of any Activity so
 * the HTTP/SOAP control thread can drive it directly.
 */
final class DlnaPlayer {
    interface Listener { void onStateChanged(); }

    static final String STOPPED = "STOPPED";
    static final String PLAYING = "PLAYING";
    static final String PAUSED = "PAUSED_PLAYBACK";
    static final String TRANSITIONING = "TRANSITIONING";
    static final String NO_MEDIA = "NO_MEDIA_PRESENT";

    private static DlnaPlayer instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Surface surface;
    private String uri;
    private String state = NO_MEDIA;
    private boolean prepared;
    private Listener listener;

    static synchronized DlnaPlayer get() {
        if (instance == null) instance = new DlnaPlayer();
        return instance;
    }

    void setListener(Listener l) { listener = l; }
    String getState() { return state; }
    String getUri() { return uri; }

    private void notifyChanged() { if (listener != null) listener.onStateChanged(); }

    synchronized void setUri(final String newUri) {
        uri = newUri;
        main.post(() -> {
            releasePlayer();
            state = newUri == null ? NO_MEDIA : STOPPED;
            prepared = false;
            notifyChanged();
        });
    }

    synchronized void setSurface(Surface s) {
        surface = s;
        main.post(this::maybePrepare);
    }

    synchronized void clearSurface() {
        surface = null;
        main.post(() -> {
            if (player != null) {
                try { player.setSurface(null); } catch (Exception ignored) { }
            }
        });
    }

    synchronized void play() {
        main.post(() -> {
            if (uri == null) return;
            if (player == null) { maybePrepare(); return; }
            if (prepared) {
                try { player.start(); state = PLAYING; } catch (Exception ignored) { }
                notifyChanged();
            }
        });
    }

    synchronized void pause() {
        main.post(() -> {
            if (player != null && prepared && player.isPlaying()) {
                try { player.pause(); state = PAUSED; } catch (Exception ignored) { }
                notifyChanged();
            }
        });
    }

    synchronized void stop() {
        main.post(() -> {
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) { }
                state = STOPPED;
                notifyChanged();
            }
        });
    }

    synchronized void seek(int ms) {
        main.post(() -> {
            if (player != null && prepared) {
                try { player.seekTo(ms); } catch (Exception ignored) { }
            }
        });
    }

    int position() {
        try { return (player != null && prepared) ? player.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    int duration() {
        try { return (player != null && prepared) ? player.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    private void maybePrepare() {
        if (uri == null || surface == null) return;
        releasePlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(uri);
            mp.setSurface(surface);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                try { p.start(); } catch (Exception ignored) { }
                state = PLAYING;
                notifyChanged();
            });
            mp.setOnCompletionListener(p -> { state = STOPPED; notifyChanged(); });
            mp.setOnErrorListener((p, what, extra) -> { state = STOPPED; notifyChanged(); return true; });
            player = mp;
            prepared = false;
            state = TRANSITIONING;
            notifyChanged();
            mp.prepareAsync();
        } catch (Exception e) {
            state = STOPPED;
            notifyChanged();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        prepared = false;
    }

    synchronized void release() {
        releasePlayer();
        state = NO_MEDIA;
        uri = null;
        notifyChanged();
    }
}
