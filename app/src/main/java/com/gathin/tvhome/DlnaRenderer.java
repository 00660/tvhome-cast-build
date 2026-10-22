package com.gathin.tvhome;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A hand-written DLNA / UPnP AV MediaRenderer (DMR). It advertises itself over SSDP, serves the
 * device and service descriptions over HTTP, accepts SOAP control (AVTransport / RenderingControl /
 * ConnectionManager) and forwards playback commands to a {@link Listener}. Pure Java sockets, so it
 * runs on Android 4.4 (API 19) with no third-party libraries.
 */
final class DlnaRenderer {
    private static final String TAG = "DlnaRenderer";
    private static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final int HTTP_PORT = 49152;

    interface Listener {
        void onSetUri(String url);
        void onPlay();
        void onPause();
        void onStop();
        void onSeek(long positionMs);
        void onVolume(int volume0to100);
        void onState(String text);
    }

    private final Context app;
    private final Listener listener;
    private ServerSocket http;
    private MulticastSocket ssdp;
    private volatile boolean running;
    private String localIp;
    private int volume = 60;
    private WifiManager.MulticastLock lock;

    DlnaRenderer(Context c, Listener l) { this.app = c.getApplicationContext(); this.listener = l; }

    void start() {
        running = true;
        localIp = findIp();
        if (localIp == null) { listener.onState("未联网，无法开启 DLNA"); return; }
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { lock = wm.createMulticastLock("dlna"); lock.setReferenceCounted(false); lock.acquire(); }
        } catch (Throwable ignored) { }
        try {
            http = new ServerSocket(HTTP_PORT);
        } catch (IOException e) {
            listener.onState("DLNA 端口被占用"); running = false; return;
        }
        new Thread(this::httpLoop, "dlna-http").start();
        new Thread(this::ssdpLoop, "dlna-ssdp").start();
        listener.onState("DLNA 已开启：" + localIp + ":" + HTTP_PORT);
    }

    void stop() {
        running = false;
        try { if (http != null) http.close(); } catch (IOException ignored) { }
        try { if (ssdp != null) ssdp.close(); } catch (Exception ignored) { }
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) { }
    }

    // ---- HTTP (device/service descriptions + SOAP control) --------------------------------------
    private void httpLoop() {
        while (running) {
            try {
                Socket s = http.accept();
                handle(s);
            } catch (IOException e) { if (!running) break; }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            String header = readHeader(in);
            if (header == null) { socket.close(); return; }
            String[] lines = header.split("\r\n");
            String[] req = lines[0].split(" ");
            String method = req[0], path = req.length > 1 ? req[1] : "/";
            String body = "";
            int len = 0;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("Content-Length"))
                    len = parseInt(lines[i].substring(c + 1).trim());
            }
            if (len > 0) body = readBody(in, len);

            if (method.equals("GET") || method.equals("HEAD")) {
                if (path.startsWith("/description.xml")) { respond(socket, 200, "text/xml; charset=\"utf-8\"", deviceDescription()); return; }
                if (path.startsWith("/AVTransport/scpd.xml")) { respond(socket, 200, "text/xml", avTransportScpd()); return; }
                if (path.startsWith("/RenderingControl/scpd.xml")) { respond(socket, 200, "text/xml", renderingControlScpd()); return; }
                if (path.startsWith("/ConnectionManager/scpd.xml")) { respond(socket, 200, "text/xml", connectionManagerScpd()); return; }
                respond(socket, 404, "text/plain", "not found"); return;
            }
            if (method.equals("POST")) {
                if (path.startsWith("/AVTransport/control")) { handleAvTransport(body); respond(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport")); return; }
                if (path.startsWith("/RenderingControl/control")) { handleRendering(body); respond(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("RenderingControl")); return; }
                if (path.startsWith("/ConnectionManager/control")) { respond(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("ConnectionManager")); return; }
                respond(socket, 404, "text/plain", "not found"); return;
            }
            if (method.equals("SUBSCRIBE")) { respond(socket, 200, "text/plain", ""); return; }
            respond(socket, 200, "text/plain", "");
        } catch (Exception e) {
            Log.w(TAG, "handle: " + e);
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void handleAvTransport(String body) {
        String action = tag(body, "u:ActionName|ActionName");
        if (action == null) action = soapAction(body);
        String uri = tag(body, "CurrentURI");
        if ("SetAVTransportURI".equals(action) && uri != null && uri.length() > 0) {
            listener.onSetUri(uri);
        } else if ("Play".equals(action)) {
            listener.onPlay();
        } else if ("Pause".equals(action)) {
            listener.onPause();
        } else if ("Stop".equals(action)) {
            listener.onStop();
        } else if ("Seek".equals(action)) {
            String target = tag(body, "Target");
            listener.onSeek(parseTime(target));
        }
    }

    private void handleRendering(String body) {
        String action = tag(body, "u:ActionName|ActionName");
        if (action == null) action = soapAction(body);
        if ("SetVolume".equals(action)) {
            String v = tag(body, "DesiredVolume");
            volume = parseInt(v);
            listener.onVolume(volume);
        }
    }

    private String soapAction(String body) {
        for (String a : new String[]{"SetAVTransportURI","Play","Pause","Stop","Seek","SetVolume","GetVolume","GetTransportInfo"})
            if (body.contains(a + " xmlns")) return a;
        return "";
    }

    // ---- SSDP ------------------------------------------------------------------------------------
    private void ssdpLoop() {
        try {
            InetAddress group = InetAddress.getByName("239.255.255.250");
            ssdp = new MulticastSocket(1900);
            ssdp.setReuseAddress(true);
            NetworkInterface nif = NetworkInterface.getByInetAddress(InetAddress.getByName(localIp));
            if (nif != null) ssdp.joinGroup(new InetSocketAddress(group, 1900), nif);
            else ssdp.joinGroup(group);
            ssdp.setSoTimeout(2000);
            byte[] buf = new byte[4096];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { ssdp.receive(p); } catch (IOException e) { continue; }
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (msg.toUpperCase().startsWith("M-SEARCH")) {
                    String st = header(msg, "ST");
                    if (st == null || st.equals("ssdp:all") || st.equals("upnp:rootdevice")
                            || st.equals(DEVICE_TYPE) || st.contains("MediaRenderer")) {
                        String resp = mSearchResponse(st == null ? DEVICE_TYPE : st);
                        byte[] out = resp.getBytes("UTF-8");
                        ssdp.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ssdp: " + e);
        }
    }

    private String mSearchResponse(String st) {
        String location = "http://" + localIp + ":" + HTTP_PORT + "/description.xml";
        return "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "EXT:\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: Android/4.4 UPnP/1.0 DlnaRenderer/1.0\r\n"
                + "ST: " + st + "\r\n"
                + "USN: uuid:tvhome-dmr-0001::" + st + "\r\n"
                + "Content-Length: 0\r\n\r\n";
    }

    // ---- descriptions ----------------------------------------------------------------------------
    private String deviceDescription() {
        String base = "http://" + localIp + ":" + HTTP_PORT;
        return "<?xml version=\"1.0\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device><deviceType>" + DEVICE_TYPE + "</deviceType><friendlyName>电视投屏</friendlyName>"
                + "<manufacturer>tvhome</manufacturer><modelName>DLNA Renderer</modelName>"
                + "<UDN>uuid:tvhome-dmr-0001</UDN>"
                + "<serviceList>"
                + svc("AVTransport", base) + svc("RenderingControl", base) + svc("ConnectionManager", base)
                + "</serviceList></device></root>";
    }

    private String svc(String name, String base) {
        return "<service><serviceType>urn:schemas-upnp-org:service:" + name + ":1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:" + name + "</serviceId>"
                + "<SCPDURL>/" + name + "/scpd.xml</SCPDURL>"
                + "<controlURL>/" + name + "/control</controlURL>"
                + "<eventSubURL>/" + name + "/event</eventSubURL></service>";
    }

    private String avTransportScpd() {
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>"
                + act("SetAVTransportURI", in("InstanceID","A_ARG_TYPE_InstanceID") + in("CurrentURI","AVTransportURI") + in("CurrentURIMetaData","AVTransportURIMetaData"))
                + act("Play", in("InstanceID","A_ARG_TYPE_InstanceID") + in("Speed","TransportPlaySpeed"))
                + act("Pause", in("InstanceID","A_ARG_TYPE_InstanceID"))
                + act("Stop", in("InstanceID","A_ARG_TYPE_InstanceID"))
                + act("Seek", in("InstanceID","A_ARG_TYPE_InstanceID") + in("Unit","A_ARG_TYPE_SeekMode") + in("Target","A_ARG_TYPE_SeekTarget"))
                + act("GetTransportInfo", in("InstanceID","A_ARG_TYPE_InstanceID") + out("CurrentTransportState","TransportState") + out("CurrentTransportStatus","TransportStatus") + out("CurrentSpeed","TransportPlaySpeed"))
                + act("GetPositionInfo", in("InstanceID","A_ARG_TYPE_InstanceID") + out("Track","CurrentTrack") + out("TrackDuration","CurrentTrackDuration") + out("TrackMetaData","CurrentTrackMetaData") + out("TrackURI","CurrentTrackURI") + out("RelTime","RelativeTimePosition") + out("AbsTime","AbsoluteTimePosition"))
                + "</actionList><serviceStateTable>"
                + var("A_ARG_TYPE_InstanceID","ui4") + var("AVTransportURI","string") + var("AVTransportURIMetaData","string")
                + var("TransportPlaySpeed","string") + var("TransportState","string") + var("TransportStatus","string")
                + var("A_ARG_TYPE_SeekMode","string") + var("A_ARG_TYPE_SeekTarget","string")
                + var("CurrentTrack","ui4") + var("CurrentTrackDuration","string") + var("CurrentTrackMetaData","string")
                + var("CurrentTrackURI","string") + var("RelativeTimePosition","string") + var("AbsoluteTimePosition","string")
                + "</serviceStateTable></scpd>";
    }

    private String renderingControlScpd() {
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>"
                + act("GetVolume", in("InstanceID","A_ARG_TYPE_InstanceID") + in("Channel","A_ARG_TYPE_Channel") + out("CurrentVolume","Volume"))
                + act("SetVolume", in("InstanceID","A_ARG_TYPE_InstanceID") + in("Channel","A_ARG_TYPE_Channel") + in("DesiredVolume","Volume"))
                + "</actionList><serviceStateTable>"
                + var("A_ARG_TYPE_InstanceID","ui4") + var("A_ARG_TYPE_Channel","string") + var("Volume","ui2")
                + "</serviceStateTable></scpd>";
    }

    private String connectionManagerScpd() {
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>" + act("GetProtocolInfo", out("Source","SourceProtocolInfo") + out("Sink","SinkProtocolInfo")) + "</actionList>"
                + "<serviceStateTable>" + var("SourceProtocolInfo","string") + var("SinkProtocolInfo","string") + "</serviceStateTable></scpd>";
    }

    private String act(String name, String args) { return "<action><name>" + name + "</name><argumentList>" + args + "</argumentList></action>"; }
    private String in(String n, String v) { return "<argument><name>" + n + "</name><direction>in</direction><relatedStateVariable>" + v + "</relatedStateVariable></argument>"; }
    private String out(String n, String v) { return "<argument><name>" + n + "</name><direction>out</direction><relatedStateVariable>" + v + "</relatedStateVariable></argument>"; }
    private String var(String n, String t) { return "<stateVariable sendEvents=\"no\"><name>" + n + "</name><dataType>" + t + "</dataType></stateVariable>"; }

    private String soapOk(String service) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + service + "Response xmlns:u=\"urn:schemas-upnp-org:service:" + service + ":1\"/>"
                + "</s:Body></s:Envelope>";
    }

    // ---- helpers ---------------------------------------------------------------------------------
    private static String tag(String xml, String names) {
        for (String name : names.split("\\|")) {
            Matcher m = Pattern.compile("<" + Pattern.quote(name) + "[^>]*>(.*?)</" + Pattern.quote(name) + ">", Pattern.DOTALL).matcher(xml);
            if (m.find()) return unescape(m.group(1).trim());
        }
        return null;
    }

    private static String header(String msg, String key) {
        for (String line : msg.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(key)) return line.substring(c + 1).trim();
        }
        return null;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static long parseTime(String t) {
        if (t == null) return 0;
        try {
            String[] p = t.split(":");
            if (p.length == 3) return (Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Long.parseLong(p[2])) * 1000;
        } catch (Exception ignored) { }
        return 0;
    }

    private static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int a = -1, b = -1, c = -1, d;
        while ((d = in.read()) != -1) {
            buf.write(d);
            if (a == '\r' && b == '\n' && c == '\r' && d == '\n') break;
            a = b; b = c; c = d;
            if (buf.size() > 16384) break;
        }
        return buf.size() == 0 ? null : buf.toString("UTF-8");
    }

    private static String readBody(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) { int r = in.read(b, off, len - off); if (r < 0) break; off += r; }
        return new String(b, 0, off, "UTF-8");
    }

    private static void respond(Socket s, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        OutputStream out = new BufferedOutputStream(s.getOutputStream());
        String reason = code == 200 ? "OK" : "Not Found";
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private String findIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            for (NetworkInterface nif : Collections.list(ifs)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (nif.getName().startsWith("wlan") || nif.getName().startsWith("eth")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Exception e) { return null; }
    }
}
