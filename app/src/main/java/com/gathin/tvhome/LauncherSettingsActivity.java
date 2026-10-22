package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Launcher settings, drawn entirely by hand (see {@link SelfDrawnUi}). No XML layout and no native
 * Button / TextView-in-a-row / AlertDialog / Toast is shown: only invisible containers plus custom
 * onDraw views. Remote-friendly: every row is focusable and activates on OK.
 */
public final class LauncherSettingsActivity extends Activity {
    private static final int PICK_WALLPAPER = 71;
    private static final int PICK_QR = 72;

    private SharedPreferences choices;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;

    private SelfDrawnUi.RowView columnsRow;
    private SelfDrawnUi.RowView animationsRow;
    private SelfDrawnUi.RowView advancedRow;
    private SelfDrawnUi.Banner banner;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        FrameLayout root = new FrameLayout(this);
        root.addView(new SelfDrawnUi.GradientView(this),
                new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padH = SelfDrawnUi.dpi(this, 72), padV = SelfDrawnUi.dpi(this, 40);
        content.setPadding(padH, padV, padH, padV);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content.addView(new SelfDrawnUi.TextLine(this, getString(R.string.launcher_settings),
                SelfDrawnUi.TextLine.TITLE), matchWrap());

        // ---- Appearance ----
        content.addView(new SelfDrawnUi.TextLine(this, getString(R.string.settings_section_appearance),
                SelfDrawnUi.TextLine.SECTION), matchWrap());
        columnsRow = new SelfDrawnUi.RowView(this, getString(R.string.columns_label), String.valueOf(columns), false);
        columnsRow.setOnClickListener(v -> {
            columns = columns >= 7 ? 4 : columns + 1;
            choices.edit().putInt("columns", columns).apply();
            setResult(RESULT_OK);
            refresh();
        });
        content.addView(columnsRow, rowLp());

        animationsRow = new SelfDrawnUi.RowView(this, getString(R.string.animations_label), null, false);
        animationsRow.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        content.addView(animationsRow, rowLp());

        advancedRow = new SelfDrawnUi.RowView(this, getString(R.string.advanced_effects), null, false);
        advancedRow.setOnClickListener(v -> {
            if (!animations) return;
            advancedEffects = !advancedEffects;
            choices.edit().putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            if (advancedEffects) banner.show(getString(R.string.advanced_effects_warning), 3500);
            refresh();
        });
        content.addView(advancedRow, rowLp());

        SelfDrawnUi.RowView followTheme = new SelfDrawnUi.RowView(this, getString(R.string.follow_theme), null, false);
        followTheme.setOnClickListener(v -> {
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            banner.show(getString(R.string.follow_theme_hint), 3500);
            refresh();
        });
        content.addView(followTheme, rowLp());

        // ---- Wallpaper ----
        content.addView(new SelfDrawnUi.TextLine(this, getString(R.string.settings_section_wallpaper),
                SelfDrawnUi.TextLine.SECTION), matchWrap());

        SelfDrawnUi.RowView wallLocal = new SelfDrawnUi.RowView(this, getString(R.string.wallpaper_local), null, true);
        wallLocal.setOnClickListener(v -> chooseLocalWallpaper());
        content.addView(wallLocal, rowLp());

        SelfDrawnUi.RowView wallNet = new SelfDrawnUi.RowView(this, getString(R.string.wallpaper_internet), null, true);
        wallNet.setOnClickListener(v ->
                startActivityForResult(new Intent(this, InternetWallpaperActivity.class), PICK_WALLPAPER));
        content.addView(wallNet, rowLp());

        SelfDrawnUi.RowView wallQr = new SelfDrawnUi.RowView(this, getString(R.string.wallpaper_qr), null, true);
        wallQr.setOnClickListener(v ->
                startActivityForResult(new Intent(this, QrUploadActivity.class), PICK_QR));
        content.addView(wallQr, rowLp());

        SelfDrawnUi.RowView wallClear = new SelfDrawnUi.RowView(this, getString(R.string.wallpaper_clear), null, false);
        wallClear.setOnClickListener(v -> clearSelectedWallpaper());
        content.addView(wallClear, rowLp());

        // ---- Screensaver & system ----
        content.addView(new SelfDrawnUi.TextLine(this, getString(R.string.settings_section_system),
                SelfDrawnUi.TextLine.SECTION), matchWrap());

        SelfDrawnUi.RowView screensaver = new SelfDrawnUi.RowView(this, getString(R.string.screensaver_enable), null, false);
        screensaver.setOnClickListener(v -> enableScreensaver());
        content.addView(screensaver, rowLp());

        SelfDrawnUi.RowView defaultHome = new SelfDrawnUi.RowView(this, getString(R.string.home_settings), null, true);
        defaultHome.setOnClickListener(v -> open(new Intent(Settings.ACTION_HOME_SETTINGS)));
        content.addView(defaultHome, rowLp());

        SelfDrawnUi.RowView tvSettings = new SelfDrawnUi.RowView(this, getString(R.string.tv_settings), null, true);
        tvSettings.setOnClickListener(v -> open(new Intent().setComponent(new ComponentName(
                "com.konka.systemsetting", "com.konka.systemsetting.MainActivity"))));
        content.addView(tvSettings, rowLp());

        banner = new SelfDrawnUi.Banner(this);
        root.addView(banner, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        refresh();
        columnsRow.post(columnsRow::requestFocus);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = SelfDrawnUi.dpi(this, 12);
        return lp;
    }

    private void refresh() {
        columnsRow.setValue(String.valueOf(columns));
        animationsRow.setValue(getString(animations ? R.string.on : R.string.off));
        advancedRow.setValue(getString(advancedEffects ? R.string.on : R.string.off));
        advancedRow.setRowEnabled(animations);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            banner.show(getString(R.string.not_available), 2500);
        }
    }

    private void chooseLocalWallpaper() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(pick, getString(R.string.wallpaper_local)), PICK_WALLPAPER);
        } catch (ActivityNotFoundException | SecurityException e) {
            banner.show(getString(R.string.wallpaper_not_found), 2500);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == PICK_QR) {
            File dir = Config.dir(this);
            File image = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
            if (image != null && image.isFile() && image.length() > 0) {
                setResult(RESULT_OK);
                banner.show(getString(R.string.wallpaper_selected), 2500);
            }
            return;
        }
        if (requestCode != PICK_WALLPAPER) return;
        if (data == null) { setResult(RESULT_OK); banner.show(getString(R.string.wallpaper_selected), 2500); return; }
        if (data.getData() == null) return;
        try {
            Uri uri = data.getData();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(getContentResolver().openInputStream(uri), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("Not an image");
            File dir = Config.dir(this);
            if (dir == null) throw new java.io.IOException("External storage unavailable");
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Cannot create wallpaper directory");
            File target = new File(dir, Config.WALLPAPER_NAME);
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new java.io.IOException("Cannot open selected image");
                byte[] buffer = new byte[8192];
                int count;
                long copied = 0;
                while ((count = in.read(buffer)) != -1) {
                    copied += count;
                    if (copied > 32L * 1024 * 1024) throw new java.io.IOException("Image is too large");
                    out.write(buffer, 0, count);
                }
            }
            setResult(RESULT_OK);
            banner.show(getString(R.string.wallpaper_selected), 2500);
        } catch (Exception e) {
            banner.show(getString(R.string.wallpaper_not_found), 3000);
        }
    }

    private void clearSelectedWallpaper() {
        File dir = Config.dir(this);
        File image = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (image != null && image.exists()) image.delete();
        setResult(RESULT_OK);
        banner.show(getString(R.string.wallpaper_clear), 2000);
    }

    private void enableScreensaver() {
        try {
            String component = getPackageName() + "/.ClockDream";
            Settings.Secure.putString(getContentResolver(), "screensaver_components", component);
            Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 1);
            Settings.Secure.putInt(getContentResolver(), "screensaver_activate_on_sleep", 1);
            banner.show(getString(R.string.screensaver_enabled), 3000);
        } catch (SecurityException e) {
            banner.show(getString(R.string.screensaver_failed), 3000);
        }
    }
}
