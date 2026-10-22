package com.gathin.tvhome;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Shared wallpaper file helpers: save from a picked Uri, clear, and existence check. */
final class Wallpapers {
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private Wallpapers() { }

    static boolean saveFromUri(Context c, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(c.getContentResolver().openInputStream(uri), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            File dir = Config.dir(c);
            if (dir == null) return false;
            if (!dir.exists() && !dir.mkdirs()) return false;
            File target = new File(dir, Config.WALLPAPER_NAME);
            try (InputStream in = c.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) return false;
                byte[] buffer = new byte[8192];
                int count;
                long copied = 0;
                while ((count = in.read(buffer)) != -1) {
                    copied += count;
                    if (copied > MAX_BYTES) return false;
                    out.write(buffer, 0, count);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void clear(Context c) {
        File dir = Config.dir(c);
        File image = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (image != null && image.exists()) image.delete();
    }

    static boolean exists(Context c) {
        File dir = Config.dir(c);
        File image = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        return image != null && image.isFile() && image.length() > 0;
    }
}
