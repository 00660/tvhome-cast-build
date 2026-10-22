package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.annotation.SuppressLint;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Android 5.0+ APIs. Loaded reflectively so Android 4.4 never resolves these framework classes. */
@SuppressLint("NewApi")
final class Api21Support {
    private final MainActivity activity;
    private final ConnectivityManager connectivity;
    private final TvInputManager tvInputs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { main.post(activity::onApi21NetworkChanged); }
        @Override public void onLost(Network network) { main.post(activity::onApi21NetworkChanged); }
    };
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { main.post(activity::onApi21NetworkChanged); }
        @Override public void onLost(Network network) { main.post(activity::onApi21NetworkChanged); }
    };
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputAdded(String inputId) { main.post(activity::onApi21InputsChanged); }
        @Override public void onInputRemoved(String inputId) { main.post(activity::onApi21InputsChanged); }
    };

    Api21Support(MainActivity activity) {
        this.activity = activity;
        connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        tvInputs = (TvInputManager) activity.getSystemService(Context.TV_INPUT_SERVICE);
    }

    void start() {
        if (connectivity != null) {
            try {
                NetworkRequest.Builder internet = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                if (Build.VERSION.SDK_INT >= 24) connectivity.registerDefaultNetworkCallback(networkCallback);
                else connectivity.registerNetworkCallback(internet.build(), networkCallback);
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(), vpnCallback);
            } catch (RuntimeException e) {
                android.util.Log.w("TvHome", "Could not observe network changes", e);
            }
        }
        if (tvInputs != null) {
            try { tvInputs.registerCallback(inputCallback, main); }
            catch (RuntimeException e) { android.util.Log.w("TvHome", "Could not observe TV inputs", e); }
        }
    }

    void stop() {
        if (tvInputs != null) {
            try { tvInputs.unregisterCallback(inputCallback); } catch (RuntimeException ignored) { }
        }
        if (connectivity != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { }
            try { connectivity.unregisterNetworkCallback(vpnCallback); } catch (RuntimeException ignored) { }
        }
    }

    boolean hasInputs() {
        if (tvInputs == null) return false;
        for (TvInputInfo info : tvInputs.getTvInputList()) {
            if (info.isPassthroughInput() && !isHidden(info)) return true;
        }
        return false;
    }

    List<MainActivity.TvInputRecord> getInputs() {
        ArrayList<MainActivity.TvInputRecord> records = new ArrayList<>();
        if (tvInputs == null) return records;
        for (TvInputInfo info : tvInputs.getTvInputList()) {
            MainActivity.TvInputRecord record = new MainActivity.TvInputRecord();
            record.id = info.getId();
            record.type = info.getType() == TvInputInfo.TYPE_HDMI ? MainActivity.TvInputRecord.TYPE_HDMI
                    : info.getType() == TvInputInfo.TYPE_TUNER ? MainActivity.TvInputRecord.TYPE_TUNER
                    : MainActivity.TvInputRecord.TYPE_OTHER;
            record.passthrough = info.isPassthroughInput();
            record.hidden = isHidden(info);
            CharSequence label = info.loadLabel(activity);
            record.label = label == null ? record.id : label.toString();
            CharSequence custom = loadCustomLabel(info);
            record.customLabel = custom == null ? null : custom.toString();
            int state = tvInputs.getInputState(record.id);
            record.state = state == TvInputManager.INPUT_STATE_CONNECTED
                    ? MainActivity.TvInputRecord.STATE_CONNECTED
                    : state == TvInputManager.INPUT_STATE_DISCONNECTED
                    ? MainActivity.TvInputRecord.STATE_DISCONNECTED : MainActivity.TvInputRecord.STATE_UNKNOWN;
            ApplicationInfo app = info.getServiceInfo().applicationInfo;
            record.system = app != null && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            record.hardware = record.id != null && record.id.matches(".*/HW\\d+$");
            records.add(record);
        }
        return records;
    }

    String passthroughUri(String inputId) {
        return TvContract.buildChannelUriForPassthroughInput(inputId).toString();
    }

    MainActivity.NetworkState networkSnapshot() {
        MainActivity.NetworkState state = new MainActivity.NetworkState();
        if (connectivity == null) return state;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                state.vpn = true;
                continue;
            }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            state.online = true;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) state.ethernet = true;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) state.wifi = true;
        }
        return state;
    }

    private boolean isHidden(TvInputInfo info) {
        if (Build.VERSION.SDK_INT < 23) return false;
        try {
            Method method = TvInputInfo.class.getMethod("isHidden", Context.class);
            return Boolean.TRUE.equals(method.invoke(info, activity));
        } catch (ReflectiveOperationException ignored) { return false; }
    }

    private CharSequence loadCustomLabel(TvInputInfo info) {
        if (Build.VERSION.SDK_INT < 24) return null;
        try {
            Method method = TvInputInfo.class.getMethod("loadCustomLabel", Context.class);
            Object value = method.invoke(info, activity);
            return value instanceof CharSequence ? (CharSequence) value : null;
        } catch (ReflectiveOperationException ignored) { return null; }
    }
}
