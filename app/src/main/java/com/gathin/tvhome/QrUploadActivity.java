package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Upload-wallpaper-by-QR screen, fully self-drawn (see {@link SelfDrawnUi}). It starts a tiny HTTP
 * server on the TV's LAN address, shows a QR code pointing at it, and writes any image the phone
 * uploads to the launcher's wallpaper file. No native dialog, button or toast is shown.
 */
public final class QrUploadActivity extends Activity {
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;
    private int port;
    private String lanIp;
    private volatile boolean running;
    private SelfDrawnUi.Banner banner;
    private SelfDrawnUi.TextLine statusLine;
    private QrView qrView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.addView(new SelfDrawnUi.GradientView(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = SelfDrawnUi.dpi(this, 72), padV = SelfDrawnUi.dpi(this, 36);
        content.setPadding(padH, padV, padH, padV);

        content.addView(new SelfDrawnUi.TextLine(this, getString(R.string.qr_title),
                SelfDrawnUi.TextLine.TITLE), wrap());
        SelfDrawnUi.TextLine hint = new SelfDrawnUi.TextLine(this, getString(R.string.qr_scan_hint),
                SelfDrawnUi.TextLine.BODY);
        LinearLayout.LayoutParams hintLp = wrap();
        hintLp.topMargin = SelfDrawnUi.dpi(this, 16);
        content.addView(hint, hintLp);

        qrView = new QrView(this);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                SelfDrawnUi.dpi(this, 300), SelfDrawnUi.dpi(this, 300));
        qrLp.topMargin = SelfDrawnUi.dpi(this, 24);
        qrLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(qrView, qrLp);

        statusLine = new SelfDrawnUi.TextLine(this, getString(R.string.qr_waiting),
                SelfDrawnUi.TextLine.SECTION);
        LinearLayout.LayoutParams stLp = wrap();
        stLp.topMargin = SelfDrawnUi.dpi(this, 20);
        content.addView(statusLine, stLp);

        SelfDrawnUi.RowView back = new SelfDrawnUi.RowView(this, getString(R.string.qr_back), null, false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                SelfDrawnUi.dpi(this, 260), -2);
        backLp.topMargin = SelfDrawnUi.dpi(this, 26);
        backLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(back, backLp);

        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        banner = new SelfDrawnUi.Banner(this);
        root.addView(banner, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        lanIp = findLanIp();
        if (lanIp == null) {
            statusLine.setText(getString(R.string.qr_server_failed));
            banner.show(getString(R.string.qr_server_failed), 4000);
            back.post(back::requestFocus);
            return;
        }
        startServer();
        back.post(back::requestFocus);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private void startServer() {
        for (int candidate : new int[]{8080, 8081, 8090, 18080}) {
            try {
                server = new ServerSocket(candidate);
                port = candidate;
                break;
            } catch (IOException ignored) { }
        }
        if (server == null) {
            statusLine.setText(getString(R.string.qr_server_failed));
            banner.show(getString(R.string.qr_server_failed), 4000);
            return;
        }
        running = true;
        final String url = "http://" + lanIp + ":" + port + "/";
        qrView.setUrl(url);
        statusLine.setText(getString(R.string.qr_waiting));
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = server.accept();
                    handle(socket);
                } catch (IOException e) {
                    if (running) continue;
                }
            }
        }, "qr-upload-server").start();
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            InputStream in = socket.getInputStream();
            String header = readHeader(in);
            if (header == null) { socket.close(); return; }
            String[] lines = header.split("\r\n");
            String requestLine = lines[0];
            String method = requestLine.split(" ")[0];
            String path = requestLine.split(" ")[1];

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(),
                        lines[i].substring(c + 1).trim());
            }

            if ("GET".equals(method) && (path.equals("/") || path.startsWith("/?"))) {
                respond(socket, 200, "text/html; charset=utf-8", uploadPage().getBytes("UTF-8"));
                return;
            }
            if ("POST".equals(method) && path.startsWith("/upload")) {
                int len = parseInt(headers.get("content-length"), 0);
                if (len <= 0 || len > MAX_BYTES) {
                    respond(socket, 400, "text/plain; charset=utf-8", "bad length".getBytes("UTF-8"));
                    return;
                }
                File dir = Config.dir(this);
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                    respond(socket, 500, "text/plain; charset=utf-8", "no storage".getBytes("UTF-8"));
                    return;
                }
                File temp = new File(dir, Config.WALLPAPER_NAME + ".upload");
                long total = 0;
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buf = new byte[16384];
                    int remaining = len, read;
                    while (remaining > 0 && (read = in.read(buf, 0, Math.min(buf.length, remaining))) != -1) {
                        out.write(buf, 0, read);
                        remaining -= read;
                        total += read;
                    }
                }
                // Validate it is really an image before replacing the wallpaper.
                BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(temp.getAbsolutePath(), b);
                if (b.outWidth <= 0 || b.outHeight <= 0) {
                    temp.delete();
                    respond(socket, 400, "text/plain; charset=utf-8", "not an image".getBytes("UTF-8"));
                    return;
                }
                File target = new File(dir, Config.WALLPAPER_NAME);
                if (target.exists() && !target.delete()) {
                    temp.delete();
                    respond(socket, 500, "text/plain; charset=utf-8", "cannot replace".getBytes("UTF-8"));
                    return;
                }
                if (!temp.renameTo(target)) {
                    temp.delete();
                    respond(socket, 500, "text/plain; charset=utf-8", "cannot save".getBytes("UTF-8"));
                    return;
                }
                respond(socket, 200, "text/plain; charset=utf-8", "ok".getBytes("UTF-8"));
                main.post(this::onUploaded);
                return;
            }
            respond(socket, 404, "text/plain; charset=utf-8", "not found".getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void onUploaded() {
        setResult(RESULT_OK);
        statusLine.setText(getString(R.string.qr_received));
        banner.show(getString(R.string.wallpaper_selected), 2500);
        main.postDelayed(this::finish, 1200);
    }

    private String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev3 = -1, prev2 = -1, prev1 = -1, c;
        while ((c = in.read()) != -1) {
            buf.write(c);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && c == '\n') break;
            prev3 = prev2; prev2 = prev1; prev1 = c;
            if (buf.size() > 16384) break;
        }
        return buf.size() == 0 ? null : buf.toString("UTF-8");
    }

    private void respond(Socket socket, int code, String contentType, byte[] body) throws IOException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private String uploadPage() {
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Upload wallpaper</title><style>"
                + "body{font-family:-apple-system,Roboto,sans-serif;background:#0e1522;color:#eef;"
                + "margin:0;padding:24px;text-align:center}"
                + "h1{font-size:20px;margin:8px 0 20px}"
                + "label{display:block;border:2px dashed #66d9c3;border-radius:16px;padding:40px 20px;"
                + "font-size:16px;color:#66d9c3;cursor:pointer}"
                + "input{display:none}"
                + "#s{margin-top:20px;font-size:15px;color:#a4b1c3;min-height:22px}"
                + "img{max-width:100%;border-radius:12px;margin-top:18px;display:none}"
                + "</style></head><body>"
                + "<h1>Upload wallpaper</h1>"
                + "<label for='f'>Tap to choose an image</label>"
                + "<input id='f' type='file' accept='image/*'>"
                + "<div id='s'></div><img id='p'>"
                + "<script>"
                + "var f=document.getElementById('f'),s=document.getElementById('s'),p=document.getElementById('p');"
                + "f.onchange=function(){var file=f.files[0];if(!file)return;"
                + "s.textContent='Uploading…';"
                + "fetch('/upload',{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:file})"
                + ".then(function(r){return r.text()}).then(function(t){"
                + "if(t==='ok'){s.textContent='Done! The TV set your wallpaper.';"
                + "p.src=URL.createObjectURL(file);p.style.display='block';}"
                + "else{s.textContent='Upload failed: '+t;}})"
                + ".catch(function(e){s.textContent='Upload failed: '+e;});};"
                + "</script></body></html>";
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private String findLanIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("eth")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    @Override protected void onDestroy() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) { }
            server = null;
        }
        super.onDestroy();
    }

    /** Draws the QR bitmap (generated once from the upload URL) centred on a white quiet-zone card. */
    private static final class QrView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Bitmap qr;

        QrView(android.content.Context c) {
            super(c);
            bgPaint.setColor(Color.WHITE);
            setFocusable(false);
        }

        void setUrl(String url) {
            try {
                int size = 512;
                Map<EncodeHintType, Object> hints = new HashMap<>();
                hints.put(EncodeHintType.MARGIN, 1);
                hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
                BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints);
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                    }
                }
                qr = bmp;
                invalidate();
            } catch (Exception e) {
                qr = null;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            float pad = SelfDrawnUi.dpi(getContext(), 10);
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            canvas.drawRoundRect(rect, SelfDrawnUi.dpi(getContext(), 16),
                    SelfDrawnUi.dpi(getContext(), 16), bgPaint);
            if (qr != null) {
                float inner = getWidth() - pad * 2 - SelfDrawnUi.dpi(getContext(), 16);
                float left = (getWidth() - inner) / 2f;
                float top = (getHeight() - inner) / 2f;
                canvas.drawBitmap(qr, null, new RectF(left, top, left + inner, top + inner), paint);
            }
        }
    }
}
