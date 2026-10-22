package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;

/**
 * Shared DLNA playback engine built on libVLC (FFmpeg-based). libVLC software-decodes formats and
 * bitrates this TV's hardware codec cannot (high-bitrate FLV/HLS), and supports Android 4.4.
 * Rendered onto the SurfaceView from {@link DlnaPlaybackActivity}.
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
    private final Context app;
    private LibVLC libVlc;
    private MediaPlayer player;
    private SurfaceView surfaceView;
    private String uri;
    private String state = NO_MEDIA;
    private Listener listener;

    private DlnaPlayer(Context c) { this.app = c.getApplicationContext(); }

    static synchronized DlnaPlayer get(Context c) {
        if (instance == null) instance = new DlnaPlayer(c);
        return instance;
    }
    static synchronized DlnaPlayer get() { return instance; }

    void setListener(Listener l) { listener = l; }
    String getState() { return state; }
    String getUri() { return uri; }
    private void notifyChanged() { if (listener != null) listener.onStateChanged(); }

    private LibVLC vlc() {
        if (libVlc == null) {
            ArrayList<String> args = new ArrayList<>();
            args.add("--no-drop-late-frames");
            args.add("--no-skip-frames");
            args.add("--avcodec-hw=none");   // force software decode (FFmpeg) — hardware can't do HD here
            args.add("--network-caching=1500");
            libVlc = new LibVLC(app, args);
        }
        return libVlc;
    }

    synchronized void setUri(final String newUri) {
        uri = newUri;
        main.post(() -> {
            releasePlayer();
            state = newUri == null ? NO_MEDIA : STOPPED;
            notifyChanged();
            if (newUri != null) maybePrepare();
        });
    }

    synchronized void setSurfaceView(final SurfaceView sv) {
        surfaceView = sv;
        main.post(() -> { if (player != null && sv != null) player.getVLCVout().setVideoView(sv); });
    }

    synchronized void play() {
        main.post(() -> {
            if (player == null) { maybePrepare(); return; }
            player.play();
            state = PLAYING; notifyChanged();
        });
    }
    synchronized void pause() {
        main.post(() -> { if (player != null && player.isPlaying()) { player.pause(); state = PAUSED; notifyChanged(); } });
    }
    synchronized void stop() {
        main.post(() -> { if (player != null) { player.stop(); state = STOPPED; notifyChanged(); } });
    }
    synchronized void seek(int ms) {
        main.post(() -> { if (player != null) player.setTime(ms); });
    }
    int position() { try { return player != null ? (int) player.getTime() : 0; } catch (Exception e) { return 0; } }
    int duration() { try { long d = player != null ? player.getLength() : 0; return d > 0 ? (int) d : 0; } catch (Exception e) { return 0; } }

    private void maybePrepare() {
        if (uri == null) return;
        releasePlayer();
        try {
            MediaPlayer p = new MediaPlayer(vlc());
            p.setEventListener(event -> {
                switch (event.type) {
                    case MediaPlayer.Event.Opening: state = TRANSITIONING; break;
                    case MediaPlayer.Event.Buffering: state = TRANSITIONING; break;
                    case MediaPlayer.Event.Playing: state = PLAYING; break;
                    case MediaPlayer.Event.Paused: state = PAUSED; break;
                    case MediaPlayer.Event.Stopped: state = STOPPED; break;
                    case MediaPlayer.Event.EndReached: state = STOPPED; break;
                    case MediaPlayer.Event.EncounteredError:
                        Log.e("DLNA", "vlc error");
                        state = STOPPED; break;
                    case MediaPlayer.Event.Vout:
                        Log.i("DLNA", "vlc vout (video output attached)"); break;
                }
                notifyChanged();
            });
            if (surfaceView != null) {
                p.getVLCVout().setVideoView(surfaceView);
            }
            p.getVLCVout().attachViews();
            syncVlcSize();
            Media m = new Media(vlc(), Uri.parse(uri));
            m.setHWDecoderEnabled(false, false);   // software decode
            p.setMedia(m);
            m.release();
            p.play();
            player = p;
            state = TRANSITIONING;
            notifyChanged();
            Log.i("DLNA", "vlc play: " + uri);
        } catch (Throwable t) {
            Log.e("DLNA", "vlc prepare failed", t);
            state = STOPPED; notifyChanged();
        }
    }

    private void syncVlcSize() {
        if (player == null || surfaceView == null) return;
        surfaceView.post(() -> {
            try {
                int w = surfaceView.getWidth(), h = surfaceView.getHeight();
                if (w > 0 && h > 0) {
                    player.getVLCVout().setWindowSize(w, h);
                    Log.i("DLNA", "vlc window size " + w + "x" + h);
                }
            } catch (Throwable t) { Log.e("DLNA", "setWindowSize fail", t); }
        });
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.getVLCVout().detachViews(); } catch (Exception ignored) { }
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    synchronized void release() {
        releasePlayer();
        state = NO_MEDIA; uri = null; notifyChanged();
    }
}
