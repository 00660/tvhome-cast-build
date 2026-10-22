package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal DLNA / UPnP AV MediaRenderer (works on Android 4.4). It advertises itself over SSDP so
 * phones' "cast / 投屏" screens find it, serves the device description, and accepts the standard
 * AVTransport / RenderingControl / ConnectionManager SOAP actions to play a pushed video/audio URL
 * full-screen via {@link DlnaPlaybackActivity}.
 */
final class DlnaServer {
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String CMS = "urn:schemas-upnp-org:service:ConnectionManager:1";

    private final Context app;
    private final String uuid;
    private volatile boolean running;
    private int httpPort;
    private String localIp;
    private ServerSocket httpServer;
    private MulticastSocket ssdpSocket;
    private Thread httpThread, ssdpThread;
    private int volume = 50;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;
    private static DlnaServer sInstance;
    static synchronized DlnaServer get(Context c) {
        if (sInstance == null) sInstance = new DlnaServer(c.getApplicationContext());
        return sInstance;
    }

    DlnaServer(Context c) {
        this.app = c.getApplicationContext();
        this.uuid = "uuid:2fac1234-31f8-11b4-a222-08002b34c003";
    }

    void start() {
        if (running) return;
        localIp = findLocalIp();
        android.util.Log.i("DLNA", "start localIp=" + localIp);
        if (localIp == null) return;
        try {
            httpServer = new ServerSocket(0);
            httpPort = httpServer.getLocalPort();
        } catch (IOException e) {
            android.util.Log.e("DLNA", "http socket failed", e);
            return;
        }
        android.util.Log.i("DLNA", "httpPort=" + httpPort + " desc=" + descriptionUrl());
        running = true;
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                    app.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("dlna-ssdp");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) { }
        httpThread = new Thread(this::httpLoop, "dlna-http");
        httpThread.setDaemon(true);
        httpThread.start();
        ssdpThread = new Thread(this::ssdpLoop, "dlna-ssdp");
        ssdpThread.setDaemon(true);
        ssdpThread.start();
    }

    void stop() {
        running = false;
        try { if (httpServer != null) httpServer.close(); } catch (IOException ignored) { }
        try { if (ssdpSocket != null) ssdpSocket.close(); } catch (Exception ignored) { }
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Exception ignored) { }
        httpServer = null;
        ssdpSocket = null;
        multicastLock = null;
    }

    // ---- SSDP ---------------------------------------------------------------------------------

    private void ssdpLoop() {
        try {
            ssdpSocket = new MulticastSocket(SSDP_PORT);
            ssdpSocket.setReuseAddress(true);
            InetAddress group = InetAddress.getByName(SSDP_ADDR);
            NetworkInterface nif = pickInterface();
            if (nif != null) ssdpSocket.setNetworkInterface(nif);
            ssdpSocket.joinGroup(group);
            android.util.Log.i("DLNA", "ssdp joined on " + (nif != null ? nif.getName() : "?"));
            for (int i = 0; i < 3; i++) { sendNotify(); try { Thread.sleep(120); } catch (Exception ignored) { } }

            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                ssdpSocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                android.util.Log.i("DLNA", "ssdp recv from " + packet.getAddress() + ": " + msg.split("\r\n")[0]);
                if (!msg.toUpperCase(Locale.US).startsWith("M-SEARCH")) continue;
                String st = headerValue(msg, "ST");
                if (st == null) continue;
                boolean match = st.equals("ssdp:all") || st.equals("upnp:rootdevice")
                        || st.equals(DEVICE_TYPE) || st.contains("MediaRenderer");
                if (!match) continue;
                String response = "HTTP/1.1 200 OK\r\n"
                        + "CACHE-CONTROL: max-age=1800\r\n"
                        + "EXT:\r\n"
                        + "LOCATION: " + descriptionUrl() + "\r\n"
                        + "SERVER: Linux/3.14 UPnP/1.0 DlnaRenderer/1.0\r\n"
                        + "ST: " + st + "\r\n"
                        + "USN: " + uuid + "::" + st + "\r\n"
                        + "Content-Length: 0\r\n\r\n";
                byte[] out = response.getBytes("UTF-8");
                ssdpSocket.send(new DatagramPacket(out, out.length, packet.getAddress(), packet.getPort()));
            }
        } catch (Exception e) {
            android.util.Log.e("DLNA", "ssdp loop error", e);
        }
    }

    private void sendNotify() {
        try {
            String[] nts = {"upnp:rootdevice", DEVICE_TYPE};
            for (String st : nts) {
                String msg = "NOTIFY * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                        + "CACHE-CONTROL: max-age=1800\r\n"
                        + "LOCATION: " + descriptionUrl() + "\r\n"
                        + "NT: " + st + "\r\n"
                        + "NTS: ssdp:alive\r\n"
                        + "SERVER: Linux/3.14 UPnP/1.0 DlnaRenderer/1.0\r\n"
                        + "USN: " + uuid + "::" + st + "\r\n\r\n";
                byte[] out = msg.getBytes("UTF-8");
                ssdpSocket.send(new DatagramPacket(out, out.length, InetAddress.getByName(SSDP_ADDR), SSDP_PORT));
            }
        } catch (Exception ignored) { }
    }

    private String descriptionUrl() { return "http://" + localIp + ":" + httpPort + "/dd.xml"; }

    // ---- HTTP ---------------------------------------------------------------------------------

    private void httpLoop() {
        while (running) {
            try {
                Socket socket = httpServer.accept();
                new Thread(() -> handle(socket), "dlna-http-conn").start();
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            if (head == null) { socket.close(); return; }
            String[] lines = head.split("\r\n");
            String requestLine = lines[0];
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            int contentLength = 0;
            String soapAction = "";
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) continue;
                String name = lines[i].substring(0, c).trim().toLowerCase(Locale.US);
                String val = lines[i].substring(c + 1).trim();
                if (name.equals("content-length")) contentLength = parseInt(val, 0);
                if (name.equals("soapaction")) soapAction = val;
            }
            byte[] body = new byte[Math.max(0, contentLength)];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }

            String pathOnly = path.split("\\?")[0];
            if (pathOnly.equals("/dd.xml")) {
                respond(socket, 200, "text/xml; charset=utf-8", descriptionXml().getBytes("UTF-8"));
            } else if (pathOnly.equals("/control") || pathOnly.startsWith("/control/")) {
                String xml = new String(body, "UTF-8");
                String resp = handleSoap(soapAction, xml);
                respond(socket, 200, "text/xml; charset=utf-8", resp.getBytes("UTF-8"));
            } else {
                respond(socket, 404, "text/plain", "not found".getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    // ---- SOAP actions -------------------------------------------------------------------------

    private String handleSoap(String soapAction, String xml) {
        String action = lastSegment(soapAction);
        if (action.isEmpty()) action = guessAction(xml);
        DlnaPlayer dp = DlnaPlayer.get();

        if (action.equals("SetAVTransportURI")) {
            String uri = extract(xml, "CurrentURI");
            dp.setUri(uri);
            // Bring the playback screen to the front (full-screen), with the pushed media URL.
            Intent i = new Intent(app, DlnaPlaybackActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("uri", uri);
            try { app.startActivity(i); } catch (Exception ignored) { }
            return soapOk(action, AVT, "");
        } else if (action.equals("Play")) {
            dp.play();
            return soapOk(action, AVT, "");
        } else if (action.equals("Pause")) {
            dp.pause();
            return soapOk(action, AVT, "");
        } else if (action.equals("Stop")) {
            dp.stop();
            return soapOk(action, AVT, "");
        } else if (action.equals("Seek")) {
            String target = extract(xml, "Target");
            dp.seek(parseTime(target));
            return soapOk(action, AVT, "");
        } else if (action.equals("GetTransportInfo")) {
            String s = dp.getState();
            return soapOk(action, AVT, "<CurrentTransportState>" + s + "</CurrentTransportState>"
                    + "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>");
        } else if (action.equals("GetPositionInfo")) {
            int dur = dp.duration(), pos = dp.position();
            return soapOk(action, AVT, "<Track>1</Track><TrackDuration>" + hms(dur) + "</TrackDuration>"
                    + "<TrackMetaData></TrackMetaData><TrackURI>" + xmlEscape(nullToEmpty(dp.getUri())) + "</TrackURI>"
                    + "<RelTime>" + hms(pos) + "</RelTime><AbsTime>" + hms(pos) + "</AbsTime>"
                    + "<RelCount>0</RelCount><AbsCount>0</AbsCount>");
        } else if (action.equals("GetMediaInfo")) {
            int dur = dp.duration();
            return soapOk(action, AVT, "<NrTracks>1</NrTracks><MediaDuration>" + hms(dur) + "</MediaDuration>"
                    + "<CurrentURI>" + xmlEscape(nullToEmpty(dp.getUri())) + "</CurrentURI>"
                    + "<CurrentURIMetaData></CurrentURIMetaData><NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                    + "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                    + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>");
        } else if (action.equals("GetDeviceCapabilities")) {
            return soapOk(action, AVT, "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia>"
                    + "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>");
        } else if (action.equals("GetTransportSettings")) {
            return soapOk(action, AVT, "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>");
        } else if (action.equals("GetVolume")) {
            return soapOk(action, RCS, "<CurrentVolume>" + volume + "</CurrentVolume>");
        } else if (action.equals("SetVolume")) {
            volume = parseInt(extract(xml, "DesiredVolume"), volume);
            return soapOk(action, RCS, "");
        } else if (action.equals("GetMute")) {
            return soapOk(action, RCS, "<CurrentMute>0</CurrentMute>");
        } else if (action.equals("SetMute")) {
            return soapOk(action, RCS, "");
        } else if (action.equals("GetCurrentConnectionIDs")) {
            return soapOk(action, CMS, "<ConnectionIDs>0</ConnectionIDs>");
        } else if (action.equals("GetCurrentConnectionInfo")) {
            return soapOk(action, CMS, "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>"
                    + "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>"
                    + "<PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status>");
        } else if (action.equals("GetProtocolInfo")) {
            return soapOk(action, CMS, "<Source></Source><Sink>"
                    + "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/avc:*,"
                    + "http-get:*:video/mpeg:*,http-get:*:video/3gpp:*,http-get:*:audio/mpeg:*,"
                    + "http-get:*:audio/mp4:*,http-get:*:audio/aac:*,http-get:*:audio/flac:*,http-get:*:*:*"
                    + "</Sink>");
        }
        return soapFault(action);
    }

    private String guessAction(String xml) {
        Matcher m = Pattern.compile("<u:([A-Za-z]+)[ >]").matcher(xml);
        if (m.find()) return m.group(1);
        Matcher m2 = Pattern.compile("<([A-Za-z]+) xmlns=\"urn:schemas-upnp-org:service").matcher(xml);
        if (m2.find()) return m2.group(1);
        return "";
    }

    private String soapOk(String action, String service, String inner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + "Response xmlns:u=\"" + service + "\">" + inner + "</u:" + action + "Response>"
                + "</s:Body></s:Envelope>";
    }

    private String soapFault(String action) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
                + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>401</errorCode>"
                + "<errorDescription>Invalid Action</errorDescription></UPnPError></detail>"
                + "</s:Fault></s:Body></s:Envelope>";
    }

    // ---- device description -------------------------------------------------------------------

    private String descriptionXml() {
        String base = "http://" + localIp + ":" + httpPort;
        return "<?xml version=\"1.0\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<deviceType>" + DEVICE_TYPE + "</deviceType>"
                + "<friendlyName>电视投屏</friendlyName>"
                + "<manufacturer>TV Launcher</manufacturer><manufacturerURL>" + base + "</manufacturerURL>"
                + "<modelDescription>DLNA MediaRenderer</modelDescription>"
                + "<modelName>TV Renderer</modelName><modelNumber>1</modelNumber><modelURL>" + base + "</modelURL>"
                + "<UDN>" + uuid + "</UDN>"
                + "<serviceList>"
                + service(AVT, "urn:schemas-upnp-org:serviceId:AVTransport", "/control", "/event")
                + service(RCS, "urn:schemas-upnp-org:serviceId:RenderingControl", "/control", "/event")
                + service(CMS, "urn:schemas-upnp-org:serviceId:ConnectionManager", "/control", "/event")
                + "</serviceList>"
                + "</device></root>";
    }

    private String service(String type, String id, String control, String event) {
        return "<service><serviceType>" + type + "</serviceType><serviceId>" + id + "</serviceId>"
                + "<controlURL>" + control + "</controlURL><eventSubURL>" + event + "</eventSubURL>"
                + "<SCPDURL>/dd.xml</SCPDURL></service>";
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void respond(Socket socket, int code, String type, byte[] body) throws IOException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String reason = code == 200 ? "OK" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int p3 = -1, p2 = -1, p1 = -1, c;
        while ((c = in.read()) != -1) {
            buf.write(c);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && c == '\n') break;
            p3 = p2; p2 = p1; p1 = c;
            if (buf.size() > 16384) break;
        }
        return buf.size() == 0 ? null : buf.toString("UTF-8");
    }

    private static String headerValue(String msg, String name) {
        for (String line : msg.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    private static String extract(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? unescape(m.group(1).trim()) : "";
    }

    private static String lastSegment(String soapAction) {
        if (soapAction == null) return "";
        soapAction = soapAction.replace("\"", "").trim();
        int i = soapAction.lastIndexOf('#');
        return i >= 0 ? soapAction.substring(i + 1) : soapAction;
    }

    private static int parseTime(String t) {
        try {
            String[] p = t.split(":");
            if (p.length == 3) return (Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + (int) Double.parseDouble(p[2])) * 1000;
        } catch (Exception ignored) { }
        return 0;
    }

    private static String hms(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String findLocalIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static NetworkInterface pickInterface() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return nif;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
