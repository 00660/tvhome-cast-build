package com.gathin.tvhome;

import io.github.hapjava.accessories.TelevisionAccessory;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.characteristics.impl.television.RemoteKeyEnum;
import io.github.hapjava.characteristics.impl.television.SleepDiscoveryModeEnum;

import java.util.concurrent.CompletableFuture;
import android.view.KeyEvent;

/**
 * A HomeKit "Television" media accessory. Active = casting/playback is on; turning it off (via the
 * Home app / Siri) stops the current cast. Input sources represent the cast receivers. This exposes
 * the TV as a HomeKit media accessory without any Android-API21+ calls.
 */
final class HomeKitTvAccessory implements TelevisionAccessory {
    private final int id;
    private final String name;
    private volatile boolean active;
    private volatile int activeIdentifier = 1;
    private volatile String configuredName = "电视投屏";
    private HomekitCharacteristicChangeCallback activeCb;
    private HomekitCharacteristicChangeCallback activeIdCb;
    private HomekitCharacteristicChangeCallback nameCb;
    private HomekitCharacteristicChangeCallback sleepCb;
    private final Runnable onActiveOn;
    private final Runnable onActiveOff;

    HomeKitTvAccessory(int id, String name, Runnable onActiveOn, Runnable onActiveOff) {
        this.id = id;
        this.name = name;
        this.onActiveOn = onActiveOn;
        this.onActiveOff = onActiveOff;
    }

    void setActiveFromPlayer(boolean a) {
        if (active != a) {
            active = a;
            if (activeCb != null) activeCb.changed();
        }
    }

    @Override public int getId() { return id; }
    @Override public CompletableFuture<String> getName() { return CompletableFuture.completedFuture(name); }
    @Override public void identify() { }
    @Override public CompletableFuture<String> getSerialNumber() { return CompletableFuture.completedFuture("TVHOME-0001"); }
    @Override public CompletableFuture<String> getModel() { return CompletableFuture.completedFuture("TV Launcher"); }
    @Override public CompletableFuture<String> getManufacturer() { return CompletableFuture.completedFuture("gathin"); }
    @Override public CompletableFuture<String> getFirmwareRevision() { return CompletableFuture.completedFuture("2.6"); }

    // ---- Television characteristics ----
    @Override public CompletableFuture<Boolean> isActive() { return CompletableFuture.completedFuture(active); }
    @Override public CompletableFuture<Void> setActive(boolean value) {
        active = value;
        try {
            if (value) { if (onActiveOn != null) onActiveOn.run(); }
            else { if (onActiveOff != null) onActiveOff.run(); }
        } catch (Throwable ignored) { }
        if (activeCb != null) activeCb.changed();
        return CompletableFuture.completedFuture(null);
    }
    @Override public void subscribeActive(HomekitCharacteristicChangeCallback cb) { activeCb = cb; }
    @Override public void unsubscribeActive() { activeCb = null; }

    @Override public CompletableFuture<Integer> getActiveIdentifier() { return CompletableFuture.completedFuture(activeIdentifier); }
    @Override public CompletableFuture<Void> setActiveIdentifier(Integer value) {
        activeIdentifier = value == null ? 1 : value;
        if (activeIdCb != null) activeIdCb.changed();
        return CompletableFuture.completedFuture(null);
    }
    @Override public void subscribeActiveIdentifier(HomekitCharacteristicChangeCallback cb) { activeIdCb = cb; }
    @Override public void unsubscribeActiveIdentifier() { activeIdCb = null; }

    @Override public CompletableFuture<String> getConfiguredName() { return CompletableFuture.completedFuture(configuredName); }
    @Override public CompletableFuture<Void> setConfiguredName(String value) {
        configuredName = value;
        if (nameCb != null) nameCb.changed();
        return CompletableFuture.completedFuture(null);
    }
    @Override public void subscribeConfiguredName(HomekitCharacteristicChangeCallback cb) { nameCb = cb; }
    @Override public void unsubscribeConfiguredName() { nameCb = null; }

    @Override public CompletableFuture<Void> setRemoteKey(RemoteKeyEnum key) {
        int code = mapKey(key);
        if (code != 0) RemoteKeys.sendKey(code);
        return CompletableFuture.completedFuture(null);
    }

    private static int mapKey(RemoteKeyEnum key) {
        if (key == null) return 0;
        switch (key) {
            case ARROW_UP: return KeyEvent.KEYCODE_DPAD_UP;
            case ARROW_DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
            case ARROW_LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case ARROW_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case SELECT: return KeyEvent.KEYCODE_DPAD_CENTER;
            case BACK: return KeyEvent.KEYCODE_BACK;
            case EXIT: return KeyEvent.KEYCODE_HOME;
            case PLAY_PAUSE: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case REWIND: return KeyEvent.KEYCODE_MEDIA_REWIND;
            case FAST_FORWARD: return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case NEXT_TRACK: return KeyEvent.KEYCODE_MEDIA_NEXT;
            case PREV_TRACK: return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case INFO: return KeyEvent.KEYCODE_INFO;
            default: return 0;
        }
    }
    @Override public CompletableFuture<SleepDiscoveryModeEnum> getSleepDiscoveryMode() {
        return CompletableFuture.completedFuture(SleepDiscoveryModeEnum.ALWAYS_DISCOVERABLE);
    }
    @Override public void subscribeSleepDiscoveryMode(HomekitCharacteristicChangeCallback cb) { sleepCb = cb; }
    @Override public void unsubscribeSleepDiscoveryMode() { sleepCb = null; }
}
