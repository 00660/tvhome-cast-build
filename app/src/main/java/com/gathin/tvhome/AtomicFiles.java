package com.gathin.tvhome;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;

/** AtomicFile helpers kept compatible with Android 4.4. */
final class AtomicFiles {
    private AtomicFiles() { }

    static byte[] readFully(AtomicFile file) throws IOException {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
