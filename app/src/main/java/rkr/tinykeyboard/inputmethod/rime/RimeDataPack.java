package rkr.tinykeyboard.inputmethod.rime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Materializes the Emscripten {@code --preload-file} pack (rime.data) into a
 * real directory tree. The pack stores the file table not in the data file
 * but in the generated rime.js; scripts/copy-rime-assets.sh extracts it into
 * the rime-data-files.txt manifest asset we consume here.
 */
public final class RimeDataPack {

    public static final class Entry {
        public final long start;
        public final long end;
        public final String path;

        Entry(long start, long end, String path) {
            this.start = start;
            this.end = end;
            this.path = path;
        }
    }

    private RimeDataPack() {
    }

    /** Parses the "<start> <end> /wasm/path" manifest lines. */
    public static List<Entry> parseManifest(String manifest) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : manifest.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int a = line.indexOf(' ');
            int b = line.indexOf(' ', a + 1);
            if (a <= 0 || b <= a) {
                throw new IOException("bad manifest line: " + line);
            }
            entries.add(new Entry(Long.parseLong(line.substring(0, a)),
                    Long.parseLong(line.substring(a + 1, b)), line.substring(b + 1)));
        }
        return entries;
    }

    /**
     * Writes every manifest entry under {@code rootDir} (the pack paths are
     * absolute like /usr/share/... and are appended to rootDir).
     */
    public static void unpack(File rootDir, InputStream pack, List<Entry> entries)
            throws IOException {
        byte[] buffer = new byte[65536];
        long pos = 0;
        for (Entry entry : entries) {
            File target = new File(rootDir, entry.path);
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            long toSkip = entry.start - pos;
            while (toSkip > 0) {
                long skipped = pack.skip(toSkip);
                if (skipped <= 0) {
                    throw new IOException("unexpected end of rime.data pack");
                }
                toSkip -= skipped;
            }
            pos = entry.start;
            OutputStream out = new FileOutputStream(target);
            try {
                long remaining = entry.end - entry.start;
                while (remaining > 0) {
                    int want = (int) Math.min(buffer.length, remaining);
                    int n = pack.read(buffer, 0, want);
                    if (n <= 0) {
                        throw new IOException("unexpected end of rime.data pack at "
                                + entry.path);
                    }
                    out.write(buffer, 0, n);
                    remaining -= n;
                    pos += n;
                }
            } finally {
                out.close();
            }
        }
    }

    /** Reads a UTF-8 stream fully (small manifest asset). */
    public static String readUtf8(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
