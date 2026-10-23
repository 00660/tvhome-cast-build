package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Renders the raw H.264 Annex-B stream from the AirPlay (UxPlay) engine with libVLC's FFmpeg
 * software decoder (the same libVLC/FFmpeg already bundled for DLNA). The stream is fed through an
 * in-process pipe (ParcelFileDescriptor) — NOT a TCP socket and NOT a second FFmpeg build — and
 * libVLC's h264 demuxer decodes it straight onto the VLCVideoLayout.
 */
final class AirPlayVideoSink {
    private static final String TAG = "AirPlaySink";

    private final Context app;
    private LibVLC libVlc;
    private MediaPlayer player;
    private VLCVideoLayout layout;
    private ParcelFileDescriptor readFd, writeFd;
    private FileOutputStream out;
    private volatile boolean running;

    AirPlayVideoSink(Context c) { this.app = c.getApplicationContext(); }

    void setLayout(VLCVideoLayout l) {
        layout = l;
        if (player != null && l != null) { try { player.attachViews(l, null, false, false); } catch (Throwable ignored) { } }
    }

    private LibVLC vlc() {
        if (libVlc == null) {
            ArrayList<String> args = new ArrayList<>();
            args.add("--avcodec-hw=none");     // FFmpeg software decode
            args.add("--no-drop-late-frames");
            args.add("--no-skip-frames");
            args.add("--clock-jitter=0");
            args.add("--clock-synchro=0");
            libVlc = new LibVLC(app, args);
        }
        return libVlc;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            readFd = pipe[0];
            writeFd = pipe[1];
            out = new FileOutputStream(writeFd.getFileDescriptor());
        } catch (Throwable t) {
            Log.e(TAG, "pipe create failed", t);
            running = false;
            return;
        }
        if (layout != null) {
            try {
                MediaPlayer p = new MediaPlayer(vlc());
                p.setEventListener(e -> {
                    if (e.type == MediaPlayer.Event.Vout) Log.i(TAG, "vlc vout");
                    if (e.type == MediaPlayer.Event.EncounteredError) Log.e(TAG, "vlc error");
                });
                p.attachViews(layout, null, false, false);
                // Read the raw H.264 Annex-B byte stream from the read end of the in-process pipe.
                Media m = new Media(vlc(), readFd.getFileDescriptor());
                m.addOption(":demux=h264");
                m.addOption(":h264-fps=30");
                m.setHWDecoderEnabled(false, false);
                p.setMedia(m);
                m.release();
                p.play();
                player = p;
                Log.i(TAG, "libVLC h264 pipe player started");
            } catch (Throwable t) {
                Log.e(TAG, "vlc start failed", t);
            }
        }
    }

    /** Feed a chunk of H.264 Annex-B bytes (from the AirPlay engine) into the pipe. */
    void write(byte[] data) {
        FileOutputStream o = out;
        if (o == null || data == null) return;
        try { o.write(data); o.flush(); } catch (Throwable ignored) { }
    }

    synchronized void stop() {
        running = false;
        try { if (out != null) out.close(); } catch (Throwable ignored) { }
        try { if (readFd != null) readFd.close(); } catch (Throwable ignored) { }
        try { if (writeFd != null) writeFd.close(); } catch (Throwable ignored) { }
        out = null; readFd = null; writeFd = null;
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.detachViews(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
    }
}
