package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Launcher settings as an in-place self-drawn modal over Home (no Activity jump). Every pixel is
 * painted by {@link SelfDrawnUi}; only invisible containers lay things out. Back / the scrim / the
 * Close row dismiss it; if any appearance choice changed, Home is rebuilt on close so the new
 * columns/animations/wallpaper take effect.
 */
final class SettingsModal {
    private final MainActivity host;
    private final SharedPreferences choices;
    private final FrameLayout root;
    private final View scrim;
    private final LinearLayout panel;
    private SelfDrawnUi.Banner banner;

    private int columns;
    private boolean animations;
    private boolean advancedEffects;
    private boolean dirty;

    private SelfDrawnUi.RowView columnsRow;
    private SelfDrawnUi.RowView animationsRow;
    private SelfDrawnUi.RowView advancedRow;
    /** The last row that had focus; used to put focus back inside the modal on return from a page. */
    private View lastFocus;

    SettingsModal(MainActivity host) {
        this.host = host;
        this.root = host.settingsRoot();
        this.choices = host.getSharedPreferences(Config.SETTINGS_NAME, android.content.Context.MODE_PRIVATE);
        Config cfg = host.settingsConfig();
        columns = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;

        android.content.Context c = host;
        banner = new SelfDrawnUi.Banner(c);

        scrim = new View(c) {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                p.setColor(0xB3000000);
                canvas.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        scrim.setClickable(true);
        scrim.setFocusable(false);
        scrim.setOnClickListener(v -> close());

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padH = SelfDrawnUi.dpi(c, 40), padV = SelfDrawnUi.dpi(c, 30);
        panel.setPadding(padH, padV, padH, padV);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF101826);
        bg.setCornerRadius(SelfDrawnUi.dpi(c, 22));
        bg.setStroke(SelfDrawnUi.dpi(c, 1), SelfDrawnUi.ACCENT);
        panel.setBackgroundDrawable(bg);

        panel.addView(new SelfDrawnUi.TextLine(c, c.getString(R.string.launcher_settings),
                SelfDrawnUi.TextLine.TITLE), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(c);
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- Appearance ----
        content.addView(new SelfDrawnUi.TextLine(c, c.getString(R.string.settings_section_appearance),
                SelfDrawnUi.TextLine.SECTION), sectionLp());
        columnsRow = new SelfDrawnUi.RowView(c, c.getString(R.string.columns_label), String.valueOf(columns), false);
        columnsRow.setOnClickListener(v -> {
            columns = columns >= 7 ? 4 : columns + 1;
            choices.edit().putInt("columns", columns).apply();
            dirty = true;
            refresh();
        });
        content.addView(columnsRow, rowLp());

        animationsRow = new SelfDrawnUi.RowView(c, c.getString(R.string.animations_label), null, false);
        animationsRow.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            dirty = true;
            refresh();
        });
        content.addView(animationsRow, rowLp());

        advancedRow = new SelfDrawnUi.RowView(c, c.getString(R.string.advanced_effects), null, false);
        advancedRow.setOnClickListener(v -> {
            if (!animations) return;
            advancedEffects = !advancedEffects;
            choices.edit().putBoolean("advanced_effects", advancedEffects).apply();
            dirty = true;
            if (advancedEffects) banner.show(c.getString(R.string.advanced_effects_warning), 3500);
            refresh();
        });
        content.addView(advancedRow, rowLp());

        SelfDrawnUi.RowView followTheme = new SelfDrawnUi.RowView(c, c.getString(R.string.follow_theme), null, false);
        followTheme.setOnClickListener(v -> {
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = host.settingsConfig();
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            dirty = true;
            banner.show(c.getString(R.string.follow_theme_hint), 3500);
            refresh();
        });
        content.addView(followTheme, rowLp());

        // ---- Wallpaper ----
        content.addView(new SelfDrawnUi.TextLine(c, c.getString(R.string.settings_section_wallpaper),
                SelfDrawnUi.TextLine.SECTION), sectionLp());

        SelfDrawnUi.RowView wallLocal = new SelfDrawnUi.RowView(c, c.getString(R.string.wallpaper_local), null, true);
        wallLocal.setOnClickListener(v -> host.settingsPickLocalWallpaper());
        content.addView(wallLocal, rowLp());

        SelfDrawnUi.RowView wallNet = new SelfDrawnUi.RowView(c, c.getString(R.string.wallpaper_internet), null, true);
        wallNet.setOnClickListener(v -> host.settingsLaunchWallpaper(
                new Intent(host, InternetWallpaperActivity.class), MainActivity.PICK_WALLPAPER_INTERNET));
        content.addView(wallNet, rowLp());

        SelfDrawnUi.RowView wallQr = new SelfDrawnUi.RowView(c, c.getString(R.string.wallpaper_qr), null, true);
        wallQr.setOnClickListener(v -> host.settingsLaunchWallpaper(
                new Intent(host, QrUploadActivity.class), MainActivity.PICK_WALLPAPER_QR));
        content.addView(wallQr, rowLp());

        SelfDrawnUi.RowView wallClear = new SelfDrawnUi.RowView(c, c.getString(R.string.wallpaper_clear), null, false);
        wallClear.setOnClickListener(v -> {
            Wallpapers.clear(host);
            dirty = true;
            banner.show(c.getString(R.string.wallpaper_clear), 2000);
        });
        content.addView(wallClear, rowLp());

        // ---- Casting ----
        content.addView(new SelfDrawnUi.TextLine(c, c.getString(R.string.settings_section_cast),
                SelfDrawnUi.TextLine.SECTION), sectionLp());

        // DLNA: ON/OFF switch (default on).
        final SelfDrawnUi.RowView dlnaSwitch = new SelfDrawnUi.RowView(c, c.getString(R.string.dlna_switch), null, false);
        dlnaSwitch.setValue(c.getString(ServiceController.isDlnaOn(c) ? R.string.on : R.string.off));
        dlnaSwitch.setOnClickListener(v -> {
            boolean on = !ServiceController.isDlnaOn(c);
            ServiceController.setDlnaOn(c, on);
            host.applyDlnaEnabled(on);
            dlnaSwitch.setValue(c.getString(on ? R.string.on : R.string.off));
        });
        content.addView(dlnaSwitch, rowLp());

        // AirPlay: a single ON/OFF switch. When on, the iPhone mirroring auto-appears full-screen.
        final SelfDrawnUi.RowView airplaySwitch = new SelfDrawnUi.RowView(c, c.getString(R.string.airplay_switch), null, false);
        airplaySwitch.setValue(c.getString(ServiceController.isAirPlayOn(c) ? R.string.on : R.string.off));
        airplaySwitch.setOnClickListener(v -> {
            boolean on = !ServiceController.isAirPlayOn(c);
            boolean ok = ServiceController.setAirPlay(c, on);
            if (on && !ok) { banner.show(c.getString(R.string.airplay_failed), 3000); }
            airplaySwitch.setValue(c.getString(on ? R.string.on : R.string.off));
        });
        content.addView(airplaySwitch, rowLp());

        final SelfDrawnUi.RowView airplayRotate = new SelfDrawnUi.RowView(c, c.getString(R.string.airplay_rotate), null, false);
        airplayRotate.setValue(c.getString(ServiceController.isAirPlayRotate(c) ? R.string.on : R.string.off));
        airplayRotate.setOnClickListener(v -> {
            boolean on = !ServiceController.isAirPlayRotate(c);
            ServiceController.setAirPlayRotate(c, on);
            airplayRotate.setValue(c.getString(on ? R.string.on : R.string.off));
        });
        content.addView(airplayRotate, rowLp());

        // HomeKit: on/off + pairing code + remote channel shown inline.
        final SelfDrawnUi.RowView homekitSwitch = new SelfDrawnUi.RowView(c, c.getString(R.string.homekit_switch), null, false);
        homekitSwitch.setValue(c.getString(ServiceController.isHomeKitOn(c) ? R.string.on : R.string.off));
        homekitSwitch.setOnClickListener(v -> {
            boolean on = !ServiceController.isHomeKitOn(c);
            ServiceController.setHomeKit(c, on);
            homekitSwitch.setValue(c.getString(on ? R.string.on : R.string.off));
        });
        content.addView(homekitSwitch, rowLp());

        content.addView(new SelfDrawnUi.TextLine(c, c.getString(R.string.homekit_pin_line,
                HomeKitManager.get().getPinText()), SelfDrawnUi.TextLine.HINT), hintLp());

        final SelfDrawnUi.RowView remoteRow = new SelfDrawnUi.RowView(c, c.getString(R.string.homekit_remote_channel), null, false);
        remoteRow.setValue(ServiceController.remoteChannel(c));
        remoteRow.setOnClickListener(v -> remoteRow.setValue(ServiceController.remoteChannel(c)));
        content.addView(remoteRow, rowLp());

        // ---- System ----
        content.addView(new SelfDrawnUi.TextLine(c, c.getString(R.string.settings_section_system),
                SelfDrawnUi.TextLine.SECTION), sectionLp());

        SelfDrawnUi.RowView screensaver = new SelfDrawnUi.RowView(c, c.getString(R.string.screensaver_enable), null, false);
        screensaver.setOnClickListener(v -> {
            try {
                String component = c.getPackageName() + "/.ClockDream";
                Settings.Secure.putString(c.getContentResolver(), "screensaver_components", component);
                Settings.Secure.putInt(c.getContentResolver(), "screensaver_enabled", 1);
                Settings.Secure.putInt(c.getContentResolver(), "screensaver_activate_on_sleep", 1);
                banner.show(c.getString(R.string.screensaver_enabled), 3000);
            } catch (SecurityException e) {
                banner.show(c.getString(R.string.screensaver_failed), 3000);
            }
        });
        content.addView(screensaver, rowLp());

        SelfDrawnUi.RowView defaultHome = new SelfDrawnUi.RowView(c, c.getString(R.string.home_settings), null, true);
        defaultHome.setOnClickListener(v -> host.settingsOpen(new Intent(Settings.ACTION_HOME_SETTINGS)));
        content.addView(defaultHome, rowLp());

        SelfDrawnUi.RowView tvSettings = new SelfDrawnUi.RowView(c, c.getString(R.string.tv_settings), null, true);
        tvSettings.setOnClickListener(v -> host.settingsOpen(new Intent().setComponent(new ComponentName(
                "com.konka.systemsetting", "com.konka.systemsetting.MainActivity"))));
        content.addView(tvSettings, rowLp());

        final SelfDrawnUi.RowView bootSwitch = new SelfDrawnUi.RowView(c, c.getString(R.string.boot_start), null, false);
        bootSwitch.setValue(c.getString(ServiceController.isBootStart(c) ? R.string.on : R.string.off));
        bootSwitch.setOnClickListener(v -> {
            boolean on = !ServiceController.isBootStart(c);
            ServiceController.setBootStart(c, on);
            bootSwitch.setValue(c.getString(on ? R.string.on : R.string.off));
        });
        content.addView(bootSwitch, rowLp());

        SelfDrawnUi.RowView close = new SelfDrawnUi.RowView(c, c.getString(R.string.qr_back), null, false);
        close.setOnClickListener(v -> close());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, -2);
        closeLp.topMargin = SelfDrawnUi.dpi(c, 20);
        panel.addView(close, closeLp);
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = SelfDrawnUi.dpi(host, 10);
        return lp;
    }

    private LinearLayout.LayoutParams hintLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = SelfDrawnUi.dpi(host, 6);
        return lp;
    }

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = SelfDrawnUi.dpi(host, 18);
        return lp;
    }

    void show() {
        int w = (int) (host.getResources().getDisplayMetrics().widthPixels * 0.62f);
        int h = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.86f);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        root.addView(panel, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        root.addView(banner, new FrameLayout.LayoutParams(-1, -1));
        // Trap remote focus inside the modal: while it is open the desktop behind it must not take focus.
        host.settingsSetContentFocusBlocked(true);
        // Remember which row has focus so we can restore it after returning from a second-level page.
        panel.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF != null && isInside(newF)) lastFocus = newF;
        });
        refresh();
        View first = (lastFocus != null && isInside(lastFocus)) ? lastFocus : columnsRow;
        first.post(first::requestFocus);
    }

    /** Re-assert the focus trap and put focus back inside the modal after returning from a page. */
    void refocus() {
        host.settingsSetContentFocusBlocked(true);
        View first = (lastFocus != null && isInside(lastFocus)) ? lastFocus : columnsRow;
        first.post(first::requestFocus);
    }

    private boolean isInside(View v) {
        View cur = v;
        while (cur != null) {
            if (cur == panel) return true;
            cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
        }
        return false;
    }

    private void refresh() {
        columnsRow.setValue(String.valueOf(columns));
        animationsRow.setValue(host.getString(animations ? R.string.on : R.string.off));
        advancedRow.setValue(host.getString(advancedEffects ? R.string.on : R.string.off));
        advancedRow.setRowEnabled(animations);
    }

    boolean attached() { return panel.getParent() != null; }

    /** Back / scrim / Close: rebuild Home only if an appearance or wallpaper choice changed. */
    void close() {
        removeViews();
        if (dirty) host.settingsRecreate();
    }

    /** Remove the modal without rebuilding Home (used when the activity pauses). */
    void discard() {
        removeViews();
    }

    private void removeViews() {
        if (panel.getParent() != null) root.removeView(panel);
        if (scrim.getParent() != null) root.removeView(scrim);
        if (banner.getParent() != null) root.removeView(banner);
        // Release the focus trap so the desktop works normally again.
        host.settingsSetContentFocusBlocked(false);
    }
}
