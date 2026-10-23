package com.gathin.tvhome.hk;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * HomeKit Television accessory model (JSON per HAP). Services: AccessoryInformation(3),
 * Television(0x D8=216), TelevisionSpeaker(0x71=113), InputSource. Characteristics handled:
 * Active(0x B0=176) and ActiveIdentifier(0x E7=231). Toggling Active drives casting on/off.
 */
public final class TvAccessory implements HapServer.Accessory {
    private static final String TAG = "TvAccessory";
    private final Runnable onActivate;    // Active -> 1
    private final Runnable onDeactivate;  // Active -> 0
    private volatile int active = 0;
    private volatile int activeIdentifier = 1;   // input source 1 (casting)

    public TvAccessory(Runnable onActivate, Runnable onDeactivate) {
        this.onActivate = onActivate;
        this.onDeactivate = onDeactivate;
    }

    public void setActive(boolean a) { active = a ? 1 : 0; }

    @Override public int aid() { return 1; }

    @Override public String accessoriesJson() {
        try {
            JSONArray accessories = new JSONArray();
            JSONObject acc = new JSONObject();
            acc.put("aid", 1);

            JSONArray services = new JSONArray();

            // Accessory Information (service type 0x3E=62)
            JSONObject info = new JSONObject();
            info.put("iid", 1);
            info.put("type", "3E");
            JSONArray ic = new JSONArray();
            ic.put(chr(2, "23", "TV Home"));                 // Name
            ic.put(chr(3, "20", "gathin"));                  // Manufacturer
            ic.put(chr(4, "21", "TVHome"));                  // Model
            ic.put(chr(5, "30", "TVHOME-0001"));             // SerialNumber
            ic.put(chr(6, "52", "2.6"));                     // FirmwareRevision
            info.put("characteristics", ic);
            services.put(info);

            // Television (service type 0xD8=216)
            JSONObject tv = new JSONObject();
            tv.put("iid", 2);
            tv.put("type", "D8");
            JSONArray tc = new JSONArray();
            tc.put(chr(7, "B0", active));                    // Active
            tc.put(chr(8, "E7", activeIdentifier));          // ActiveIdentifier
            tc.put(chr(9, "23", "电视投屏"));                  // ConfiguredName
            tc.put(chr(10, "DB", "1"));                       // SleepDiscoveryMode = ALWAYS_DISCOVERABLE
            tv.put("characteristics", tc);
            services.put(tv);

            // Television Speaker (service type 0x71=113)
            JSONObject spk = new JSONObject();
            spk.put("iid", 3);
            spk.put("type", "71");
            JSONArray sc = new JSONArray();
            sc.put(chr(11, "B0", 0));                         // Mute=false
            spk.put("characteristics", sc);
            services.put(spk);

            // Input Source (service type 0xD9=217) "Casting"
            JSONObject in = new JSONObject();
            in.put("iid", 4);
            in.put("type", "D9");
            JSONArray inc = new JSONArray();
            inc.put(chr(12, "DB", "1"));                       // Identifier
            inc.put(chr(13, "D0", 0));                         // ConfiguredName?(string) -> use Name
            inc.put(chr(14, "DC", "1"));                       // CurrentVisibilityState? keep simple
            in.put("characteristics", inc);
            services.put(in);

            acc.put("services", services);
            accessories.put(acc);
            JSONObject root = new JSONObject();
            root.put("accessories", accessories);
            return root.toString();
        } catch (Throwable t) { Log.e(TAG, "json", t); return "{\"accessories\":[]}"; }
    }

    private static JSONObject chr(int iid, String type, Object value) throws Exception {
        JSONObject c = new JSONObject();
        c.put("iid", iid);
        c.put("type", type);
        JSONObject v = new JSONObject(); v.put("value", value);
        c.put("value", value);
        return c;
    }

    @Override public String onCharacteristics(String method, String query, String body) {
        try {
            if ("GET".equals(method)) {
                // Return current values for requested ids (best-effort)
                JSONArray arr = new JSONArray();
                JSONObject o = new JSONObject();
                o.put("aid", 1); o.put("iid", 7); o.put("value", active);
                arr.put(o);
                JSONObject r = new JSONObject(); r.put("characteristics", arr);
                return r.toString();
            } else { // PUT
                JSONObject req = new JSONObject(body);
                JSONArray arr = req.optJSONArray("characteristics");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        int iid = o.optInt("iid", -1);
                        if (iid == 7) { // Active
                            int v = o.optInt("value", 0);
                            active = v;
                            if (v == 1) { if (onActivate != null) onActivate.run(); }
                            else { if (onDeactivate != null) onDeactivate.run(); }
                        } else if (iid == 8) {
                            activeIdentifier = o.optInt("value", 1);
                        }
                    }
                }
                JSONObject r = new JSONObject(); r.put("characteristics", new JSONArray());
                return r.toString();
            }
        } catch (Throwable t) { return "{\"status\":-70402}"; }
    }
}
