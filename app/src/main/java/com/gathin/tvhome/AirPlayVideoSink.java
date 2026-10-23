package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

/**
 * Renders the raw H.264 Annex-B stream from the AirPlay (UxPlay) engine with libVLC software
 * decoding. UxPlay hands us bare H.264 NAL units; we relay them through a loopback TCP socket and
 * let libVLC's h264 demuxer decode + display them full-screen in a VLCVideoLayout. This avoids the
 * TV's weak hardware decoder (the reason mirroring showed nothing/only-audio before).
 */
final class AirPlayVideoSink {
    private static final String TAG = "AirPlaySink";

    private final Context app;
    private LibVLC libVlc;
    private MediaPlayer player;
    private VLCVideoLayout layout;
    private ServerSocket server;
    private volatile Socket client;
    private volatile OutputStream out;
    private volatile boolean running;
    private int port;

    AirPlayVideoSink(Context c) { this.app = c.getApplicationContext(); }

    void setLayout(VLCVideoLayout l) {
        layout = l;
        if (player != null && l != null) {
            try { player.attachViews(l, null, false, false); } catch (Throwable ignored) { }
        }
    }

    private LibVLC vlc() {
        if (libVlc == null) {
            ArrayList<String> args = new ArrayList<>();
            args.add("--avcodec-hw=none");
            args.add("--no-drop-late-frames");
            args.add("--no-skip-frames");
            args.add("--clock-jitter=0");
            args.add("--clock-synchro=0");
            libVlc = new LibVLC(app, args);
        }
        return libVlc;
    }

    /** Start the loopback server + libVLC player. Safe to call once per mirror session. */
    void start() {
        if (running) return;
        running = true;
        try {
            server = new ServerSocket(0);
            port = server.getLocalPort();
        } catch (Exception e) {
            Log.e(TAG, "server socket failed", e);
            running = false;
            return;
        }
        new Thread(() -> {
            try {
                Socket s = server.accept();
                client = s;
                out = s.getOutputStream();
                Log.i(TAG, "h264 client connected");
            } catch (Exception e) {
                Log.e(TAG, "accept failed", e);
            }
        }, "airplay-h264-accept").start();

        if (layout != null) {
            try {
                MediaPlayer p = new MediaPlayer(vlc());
                p.setEventListener(e -> {
                    if (e.type == MediaPlayer.Event.Vout) Log.i(TAG, "vlc vout");
                    if (e.type == MediaPlayer.Event.EncounteredError) Log.e(TAG, "vlc error");
                });
                p.attachViews(layout, null, false, false);
                Media m = new Media(vlc(), Uri.parse("tcp://127.0.0.1:" + port));
                m.addOption(":demux=h264");
                m.addOption(":h264-fps=30");
                // AirPlay mirror from a phone is usually portrait; rotate 90° so it fills the TV screen.
                // (Video apps that cast landscape already send landscape, and rotation is skipped via setting.)
                if (ServiceController.isAirPlayRotate(app)) {
                    m.addOption(":video-filter=transform");
                    m.addOption(":transform-type=90");
                }
                m.setHWDecoderEnabled(false, false);
                p.setMedia(m);
                m.release();
                p.play();
                player = p;
                Log.i(TAG, "vlc h264 player on tcp " + port);
            } catch (Throwable t) {
                Log.e(TAG, "vlc start failed", t);
            }
        }
    }

    /** Feed a chunk of H.264 Annex-B bytes from the AirPlay engine. */
    void write(byte[] data) {
        OutputStream o = out;
        if (o == null || data == null) return;
        try {
            o.write(data);
            o.flush();
        } catch (Exception e) {
            // client gone; ignore until next start
        }
    }

    void stop() {
        running = false;
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        try { if (client != null) client.close(); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        out = null; client = null; server = null;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.detachViews(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }
}
