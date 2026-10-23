package com.gathin.tvhome.hk;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * HomeKit Television accessory (JSON per HAP). Implements the spec-required services/characteristics
 * with correct short UUIDs and per-characteristic perms/format. RemoteKey (E1) drives the TV's remote
 * keys; Active (B0) drives casting; ActiveIdentifier (E7) selects the single "AirPlay" input source.
 * UUID short codes are the authoritative HAP values.
 */
public final class TvAccessory implements HapServer.Accessory {
    private static final String TAG = "TvAccessory";

    public interface RemoteSink { void onRemoteKey(int key); }
    private final Runnable onActivate;
    private final Runnable onDeactivate;
    private final RemoteSink remoteSink;

    private static final int IID_SERVICE_INFORMATION = 1, IID_SERVICE_TELEVISION = 18;
    private static final int IID_SERVICE_SPEAKER = 19, IID_SERVICE_INPUT_SOURCE = 20;
    private volatile int active = 0;
    private volatile int activeIdentifier = IID_SERVICE_INPUT_SOURCE;

    // iids
    private static final int IID_INFO_NAME = 2, IID_INFO_MFR = 3, IID_INFO_MODEL = 4, IID_INFO_SERIAL = 5, IID_INFO_FW = 6;
    private static final int IID_TV_ACTIVE = 7, IID_TV_ACTIVE_ID = 8, IID_TV_CONFIGURED_NAME = 9, IID_TV_REMOTE = 10, IID_TV_SLEEP = 11;
    private static final int IID_SPK_MUTE = 12;
    private static final int IID_IN_CONFIGURED_NAME = 13, IID_IN_TYPE = 14, IID_IN_ISCONF = 15, IID_IN_NAME = 16, IID_IN_VIS = 17;

    public TvAccessory(Runnable onActivate, Runnable onDeactivate, RemoteSink remoteSink) {
        this.onActivate = onActivate; this.onDeactivate = onDeactivate; this.remoteSink = remoteSink;
    }

    public void setActive(boolean a) { active = a ? 1 : 0; }
    @Override public int aid() { return 1; }

    @Override public String accessoriesJson() {
        try {
            JSONArray services = new JSONArray();

            // AccessoryInformation (3E)
            JSONObject info = svc(IID_SERVICE_INFORMATION, "3E");
            JSONArray ic = new JSONArray();
            ic.put(chr(IID_INFO_NAME, "23", "Name", "string", "pr", "电视投屏"));
            ic.put(chr(IID_INFO_MFR, "20", "Manufacturer", "string", "pr", "gathin"));
            ic.put(chr(IID_INFO_MODEL, "21", "Model", "string", "pr", "TVHome"));
            ic.put(chr(IID_INFO_SERIAL, "30", "SerialNumber", "string", "pr", "TVHOME-0001"));
            ic.put(chr(IID_INFO_FW, "52", "FirmwareRevision", "string", "pr", "2.6"));
            info.put("characteristics", ic);
            services.put(info);

            // Television (D8): Active, ActiveIdentifier, ConfiguredName, RemoteKey, SleepDiscoveryMode
            JSONObject tv = svc(IID_SERVICE_TELEVISION, "D8");
            JSONArray tc = new JSONArray();
            tc.put(chrPerm(IID_TV_ACTIVE, "B0", "Active", "uint8", "prpw", active, 0, 1));
            tc.put(chrPerm(IID_TV_ACTIVE_ID, "E7", "ActiveIdentifier", "uint32", "prpw", activeIdentifier));
            tc.put(chrPerm(IID_TV_CONFIGURED_NAME, "E3", "ConfiguredName", "string", "prpw", "电视投屏"));
            JSONObject rk = chrPerm(IID_TV_REMOTE, "E1", "RemoteKey", "uint8", "pw", 0);
            tc.put(rk);
            tc.put(chrPerm(IID_TV_SLEEP, "E8", "SleepDiscoveryMode", "uint8", "pr", 1, 0, 1));
            tv.put("characteristics", tc);
            JSONArray linked = new JSONArray();
            linked.put(IID_SERVICE_SPEAKER);
            linked.put(IID_SERVICE_INPUT_SOURCE);
            tv.put("linked", linked);
            tv.put("primary", true);
            services.put(tv);

            // TelevisionSpeaker (113): Mute
            JSONObject spk = svc(IID_SERVICE_SPEAKER, "113");
            JSONArray sc = new JSONArray();
            sc.put(chrPerm(IID_SPK_MUTE, "11A", "Mute", "bool", "prpw", false));
            spk.put("characteristics", sc);
            services.put(spk);

            // InputSource (D9): ConfiguredName, InputSourceType, IsConfigured, Name, CurrentVisibilityState
            JSONObject in = svc(IID_SERVICE_INPUT_SOURCE, "D9");
            JSONArray inc = new JSONArray();
            inc.put(chrPerm(IID_IN_CONFIGURED_NAME, "E3", "ConfiguredName", "string", "prpw", "AirPlay"));
            inc.put(chrPerm(IID_IN_TYPE, "DB", "InputSourceType", "uint8", "pr", 8));  // 8 = AIRPLAY
            inc.put(chrPerm(IID_IN_ISCONF, "D6", "IsConfigured", "uint8", "prpw", 1, 0, 1));
            inc.put(chr(IID_IN_NAME, "23", "Name", "string", "pr", "AirPlay"));
            inc.put(chrPerm(IID_IN_VIS, "135", "CurrentVisibilityState", "uint8", "pr", 0)); // 0 = SHOWN
            in.put("characteristics", inc);
            services.put(in);

            JSONObject acc = new JSONObject();
            acc.put("aid", 1);
            acc.put("services", services);
            JSONArray arr = new JSONArray(); arr.put(acc);
            JSONObject root = new JSONObject(); root.put("accessories", arr);
            return root.toString();
        } catch (Throwable t) { Log.e(TAG, "json", t); return "{\"accessories\":[]}"; }
    }

    @Override public String onCharacteristics(String method, String query, String body) {
        try {
            if ("GET".equals(method)) {
                // query is "id=1.7,1.8,..."; return each requested iid's current value.
                JSONArray out = new JSONArray();
                if (query != null && query.startsWith("id=")) {
                    String ids = query.substring(3);
                    for (String pair : ids.split(",")) {
                        String[] ap = pair.split("\\.");
                        if (ap.length != 2) continue;
                        int aid = parseInt(ap[0]), iid = parseInt(ap[1]);
                        JSONObject o = new JSONObject();
                        o.put("aid", aid); o.put("iid", iid);
                        o.put("value", currentValue(iid));
                        out.put(o);
                    }
                }
                JSONObject r = new JSONObject(); r.put("characteristics", out);
                return r.toString();
            } else { // PUT
                JSONObject req = new JSONObject(body);
                JSONArray arr = req.optJSONArray("characteristics");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        int iid = o.optInt("iid", -1);
                        if (iid == IID_TV_ACTIVE) {
                            active = o.optInt("value", 0);
                            if (active == 1) { if (onActivate != null) onActivate.run(); }
                            else { if (onDeactivate != null) onDeactivate.run(); }
                        } else if (iid == IID_TV_ACTIVE_ID) {
                            activeIdentifier = o.optInt("value", 1);
                        } else if (iid == IID_TV_REMOTE) {
                            int key = o.optInt("value", -1);
                            if (key >= 0 && remoteSink != null) remoteSink.onRemoteKey(key);
                        }
                    }
                }
                JSONObject r = new JSONObject(); r.put("characteristics", new JSONArray());
                return r.toString();
            }
        } catch (Throwable t) { return "{\"status\":-70402}"; }
    }

    private Object currentValue(int iid) {
        switch (iid) {
            case IID_TV_ACTIVE: return active;
            case IID_TV_ACTIVE_ID: return activeIdentifier;
            case IID_TV_CONFIGURED_NAME: return "电视投屏";
            case IID_TV_SLEEP: return 1;
            case IID_SPK_MUTE: return false;
            case IID_IN_TYPE: return 8;
            case IID_IN_ISCONF: return 1;
            case IID_IN_VIS: return 0;
            default: return 0;
        }
    }

    private static JSONObject svc(int iid, String type) throws Exception {
        JSONObject s = new JSONObject(); s.put("iid", iid); s.put("type", type); return s;
    }
    private static JSONObject chr(int iid, String type, String desc, String format, String perms, Object value) throws Exception {
        JSONObject c = new JSONObject();
        c.put("iid", iid); c.put("type", type);
        JSONObject d = new JSONObject(); d.put("format", format); d.put("perms", permsArr(perms)); d.put("desc", desc);
        c.put("description", desc);
        c.put("format", format);
        c.put("perms", permsArr(perms));
        c.put("value", value);
        return c;
    }
    private static JSONObject chrPerm(int iid, String type, String desc, String format, String perms, Object value) throws Exception {
        return chr(iid, type, desc, format, perms, value);
    }
    private static JSONObject chrPerm(int iid, String type, String desc, String format, String perms, Object value, int min, int max) throws Exception {
        JSONObject c = chr(iid, type, desc, format, perms, value);
        c.put("minValue", min); c.put("maxValue", max); c.put("minStep", 1);
        return c;
    }
    private static JSONArray permsArr(String perms) {
        JSONArray out = new JSONArray();
        if ("prpw".equals(perms)) { out.put("pr"); out.put("pw"); }
        else if ("pr".equals(perms)) { out.put("pr"); }
        else if ("pw".equals(perms)) { out.put("pw"); }
        else out.put("pr");
        // HAP also requires "ev" (events) on characteristics that support notifications; add it for rw ones
        if ("prpw".equals(perms)) out.put("ev");
        return out;
    }

    private static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; } }
}
