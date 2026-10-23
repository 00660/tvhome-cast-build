package com.gathin.tvhome.hk;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal HAP accessory HTTP server, implemented from the HAP spec (no external library):
 *   /pair-setup  (M1..M6)   /pair-verify (M1..M4)   /accessories   /characteristics
 * Runs pair-setup/verify in plaintext, then upgrades the connection to ChaCha20-Poly1305. Exposes a
 * Television media accessory. Android 4.4-safe (plain sockets + threads + BouncyCastle).
 */
public final class HapServer {
    private static final String TAG = "HapServer";
    private final Context app;
    private final HapKeyStore keys;
    private final Accessory accessory;
    private final int port;
    private volatile boolean running;
    private ServerSocket serverSocket;

    interface Accessory {
        String accessoriesJson();
        /** Handle GET/PUT /characteristics; returns JSON string. */
        String onCharacteristics(String method, String query, String body);
        int aid();
    }

    public HapServer(Context c, Accessory accessory, int port) {
        this.app = c.getApplicationContext();
        this.keys = new HapKeyStore(app);
        this.accessory = accessory;
        this.port = port;
    }

    public String accessoryId() { return keys.accessoryId(); }

    public void start() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Log.i(TAG, "HAP listening on " + port);
                while (running) {
                    final Socket s = serverSocket.accept();
                    new Thread(() -> handleConn(s), "hap-conn").start();
                }
            } catch (Throwable t) { Log.e(TAG, "server", t); }
        }, "hap-accept").start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) { }
    }

    // ---- pairing session stores ----
    private void handleConn(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            HapPairSetup setup = null;
            HapPairVerify verify = null;

            while (running) {
                String head = readHttpHead(in);
                if (head == null) break;
                int contentLength = 0;
                String first = head.split("\r\n")[0];
                for (String line : head.split("\r\n")) {
                    int c = line.indexOf(':');
                    if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length"))
                        contentLength = Integer.parseInt(line.substring(c + 1).trim());
                }
                byte[] body = readFully(in, contentLength);
                String[] req = first.split(" ");
                String method = req[0];
                String path = req.length > 1 ? req[1] : "/";
                String pathOnly = path.split("\\?")[0];

                byte[] respBody;
                String respType = "application/hap+json";

                if (pathOnly.equals("/pair-setup")) {
                    if (setup == null) setup = new HapPairSetup(setupStore(), pin());
                    respBody = setup.handle(body);
                    respType = "application/pairing+tlv8";
                    writeResp(out, 200, respType, respBody);
                } else if (pathOnly.equals("/pair-verify")) {
                    if (verify == null) { verify = new HapPairVerify(verifyStore()); }
                    // verify M1 returns M2 (plaintext); then M3/M4 plaintext, then switch to encrypted
                    respBody = verify.handle(body);
                    writeResp(out, 200, "application/pairing+tlv8", respBody);
                    if (respBody.length >= 2 && respBody[0] == Tlv8.STATE && respBody[2] == 4) {
                        // Pair verify complete → wrap streams in cipher for the rest of the connection
                        HapCipher cipher = new HapCipher(in, out, verify.writeKey, verify.readKey);
                        serveEncrypted(cipher);
                        break;
                    }
                } else {
                    // Unencrypted non-pairing endpoint: require encrypted (shouldn't happen after verify)
                    writeResp(out, 470, "application/hap+json",
                            "{\"status\":-70402}".getBytes("UTF-8"));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "conn", t);
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    /** Serve HAP requests over an established encrypted session. */
    private void serveEncrypted(HapCipher cipher) {
        try {
            while (running) {
                byte[] plain = cipher.read();
                if (plain == null) break;
                String req = new String(plain, "UTF-8");
                String head = req.substring(0, req.indexOf("\r\n\r\n") + 4);
                String bodyStr = req.substring(req.indexOf("\r\n\r\n") + 4);
                String first = head.split("\r\n")[0];
                String[] parts = first.split(" ");
                String method = parts[0];
                String path = parts[1];
                String pathOnly = path.split("\\?")[0];
                String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";

                int status = 200;
                String json;
                if (pathOnly.equals("/accessories")) {
                    json = accessory.accessoriesJson();
                } else if (pathOnly.equals("/characteristics")) {
                    json = accessory.onCharacteristics(method, query, bodyStr);
                } else {
                    status = 404; json = "{}";
                }
                String resp = "HTTP/1.1 " + status + " OK\r\nContent-Type: application/hap+json\r\nContent-Length: "
                        + json.getBytes("UTF-8").length + "\r\n\r\n" + json;
                cipher.write(resp.getBytes("UTF-8"));
            }
        } catch (Throwable t) { Log.e(TAG, "enc", t); }
    }

    private String pin() {
        // Stable setup code. HAP requires format with no ambiguous chars.
        return "12344321";
    }

    private HapPairSetup.Store setupStore() {
        return new HapPairSetup.Store() {
            public String accessoryPairingId() { return keys.accessoryId(); }
            public byte[] accessoryEd25519Public() { return keys.ed25519Public(); }
            public byte[] accessoryEd25519Sign(byte[] d) { return keys.ed25519Sign(d); }
            public void saveController(String id, byte[] ltpk) { keys.saveController(id, ltpk); }
            public byte[] controllerLtpkPublic() { return keys.ed25519Public(); }
        };
    }
    private HapPairVerify.Store verifyStore() {
        return new HapPairVerify.Store() {
            public byte[] controllerLtpk(String id) { return keys.controllerLtpk(id); }
            public String accessoryPairingId() { return keys.accessoryId(); }
            public byte[] accessoryEd25519Public() { return keys.ed25519Public(); }
            public byte[] accessoryEd25519Sign(byte[] d) { return keys.ed25519Sign(d); }
        };
    }

    // ---- HTTP helpers ----
    private static String readHttpHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int p3 = -1, p2 = -1, p1 = -1, c;
        while ((c = in.read()) != -1) {
            b.write(c);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && c == '\n') break;
            p3 = p2; p2 = p1; p1 = c;
            if (b.size() > 16384) break;
        }
        return b.size() == 0 ? null : b.toString("UTF-8");
    }
    private static byte[] readFully(InputStream in, int n) throws Exception {
        if (n <= 0) return new byte[0];
        byte[] b = new byte[n]; int off = 0;
        while (off < n) { int r = in.read(b, off, n - off); if (r < 0) break; off += r; }
        return b;
    }
    private static void writeResp(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n\r\n";
        out.write(head.getBytes("UTF-8")); out.write(body); out.flush();
    }
}
