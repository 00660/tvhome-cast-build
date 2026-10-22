package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

/**
 * Shared DLNA playback engine built on ExoPlayer 2.x (supports FLV/HLS/MP4 that phones push, and
 * works on Android 4.4 / API19). Rendered onto the Surface from {@link DlnaPlaybackActivity}.
 * ExoPlayer decodes with the TV's hardware codecs via MediaCodec but with proper FLV/HLS demuxing,
 * which the stock MediaPlayer on this old TV lacks (that caused audio-only black video).
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
    private ExoPlayer player;
    private Surface surface;
    private String uri;
    private String state = NO_MEDIA;
    private boolean prepared;
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

    synchronized void setUri(final String newUri) {
        uri = newUri;
        main.post(() -> {
            releasePlayer();
            state = newUri == null ? NO_MEDIA : STOPPED;
            prepared = false;
            notifyChanged();
            if (newUri != null) maybePrepare();
        });
    }

    synchronized void setSurface(Surface s) {
        surface = s;
        main.post(() -> {
            if (player != null) player.setVideoSurface(surface);
            else maybePrepare();
        });
    }

    synchronized void clearSurface() {
        surface = null;
        main.post(() -> { if (player != null) player.setVideoSurface(null); });
    }

    synchronized void play() {
        main.post(() -> {
            if (player == null) { maybePrepare(); return; }
            player.setPlayWhenReady(true);
            state = PLAYING; notifyChanged();
        });
    }

    synchronized void pause() {
        main.post(() -> {
            if (player != null) { player.setPlayWhenReady(false); state = PAUSED; notifyChanged(); }
        });
    }

    synchronized void stop() {
        main.post(() -> {
            if (player != null) { player.stop(); state = STOPPED; notifyChanged(); }
        });
    }

    synchronized void seek(int ms) {
        main.post(() -> { if (player != null) player.seekTo(ms); });
    }

    int position() {
        try { return player != null ? (int) player.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }
    int duration() {
        try {
            long d = player != null ? player.getDuration() : 0;
            return d > 0 ? (int) d : 0;
        } catch (Exception e) { return 0; }
    }

    private void maybePrepare() {
        if (uri == null) return;
        releasePlayer();
        try {
            ExoPlayer p = new ExoPlayer.Builder(app).build();
            p.setAudioAttributes(new com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
            if (surface != null) p.setVideoSurface(surface);
            p.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    Log.i("DLNA", "exo state=" + playbackState);
                    if (playbackState == Player.STATE_READY) { prepared = true; state = PLAYING; }
                    else if (playbackState == Player.STATE_BUFFERING) state = TRANSITIONING;
                    else if (playbackState == Player.STATE_ENDED) state = STOPPED;
                    notifyChanged();
                }
                @Override public void onPlayerError(PlaybackException error) {
                    Log.e("DLNA", "exo error: " + error.getErrorCodeName() + " " + error.getMessage(), error);
                    state = STOPPED; notifyChanged();
                }
                @Override public void onVideoSizeChanged(com.google.android.exoplayer2.video.VideoSize vs) {
                    Log.i("DLNA", "exo videoSize " + vs.width + "x" + vs.height);
                }
            });
            DefaultDataSource.Factory ds = new DefaultDataSource.Factory(app,
                    new DefaultHttpDataSource.Factory().setUserAgent("DLNA/1.0").setAllowCrossProtocolRedirects(true));
            Uri u = Uri.parse(uri);
            String path = u.getPath() == null ? "" : u.getPath().toLowerCase();
            MediaSource src;
            if (path.endsWith(".m3u8")) {
                src = new HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(u));
            } else {
                src = new ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(u));
            }
            p.setMediaSource(src);
            p.setPlayWhenReady(true);
            p.prepare();
            player = p;
            prepared = false;
            state = TRANSITIONING;
            notifyChanged();
        } catch (Throwable t) {
            Log.e("DLNA", "exo prepare failed", t);
            state = STOPPED; notifyChanged();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        prepared = false;
    }

    synchronized void release() {
        releasePlayer();
        state = NO_MEDIA; uri = null; notifyChanged();
    }
}
