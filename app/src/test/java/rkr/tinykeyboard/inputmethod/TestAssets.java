package rkr.tinykeyboard.inputmethod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class TestAssets {
    private TestAssets() {
    }

    /** Test workers may run with either the module dir or the repo root as working dir. */
    static File asset(String name) {
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path candidate = workingDir.resolve("src/main/assets").resolve(name);
        if (!Files.exists(candidate)) {
            candidate = workingDir.resolve("app/src/main/assets").resolve(name);
        }
        return candidate.toFile();
    }

    /** Copies a bundled asset to a temp file, e.g. so sqlite-jdbc can open it safely. */
    static File copiedAsset(String name) throws IOException {
        File target = File.createTempFile("hallelujah-", "-" + name);
        target.deleteOnExit();
        try (InputStream in = new FileInputStream(asset(name));
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return target;
    }
}
