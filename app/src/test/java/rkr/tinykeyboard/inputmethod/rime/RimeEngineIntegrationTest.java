package rkr.tinykeyboard.inputmethod.rime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import rkr.tinykeyboard.inputmethod.TestAssets;

/**
 * Boots the real rime.wasm (my_rime dist) on endive with the Java port of
 * the emscripten host environment, then types pinyin end to end. This is the
 * acceptance test for the whole engine stack before Android wiring.
 */
public class RimeEngineIntegrationTest {

    private static RimeWasmEngine engine;
    private static File rootDir;
    private static final AtomicReference<String> lastDeployStatus = new AtomicReference<>();

    @BeforeClass
    public static void setUp() throws Exception {
        RimeLog.setLogger(new RimeLog.Logger() {
            @Override
            public void log(String tag, String message, Throwable error) {
                System.out.println("[rime] " + tag + ": " + message
                        + (error == null ? "" : " (" + error + ")"));
            }
        });

        rootDir = Files.createTempDirectory("rime-root").toFile();
        // 1. unpack the rime.data preload pack into usr/share/...
        try (InputStream pack = new FileInputStream(TestAssets.asset("rime/rime.data"))) {
            String manifest = RimeDataPack.readUtf8(
                    new FileInputStream(TestAssets.asset("rime/rime-data-files.txt")));
            RimeDataPack.unpack(rootDir, pack, RimeDataPack.parseManifest(manifest));
        }
        // 2. place the prebuilt luna_pinyin schema into the shared build dir
        File buildDir = new File(rootDir, "usr/share/rime-data/build");
        assertTrue(buildDir.isDirectory());
        File schemaSrc = TestAssets.asset("rime/luna-pinyin");
        File[] schemaFiles = schemaSrc.listFiles();
        assertNotNull(schemaFiles);
        for (File f : schemaFiles) {
            copy(f, new File(buildDir, f.getName()));
        }
        // 3. the rime user directory
        assertTrue(new File(rootDir, "rime").mkdirs());

        long t0 = System.currentTimeMillis();
        byte[] wasm = readAll(new FileInputStream(TestAssets.asset("rime/rime.wasm")));
        engine = RimeWasmEngine.create(rootDir, wasm, new RimeWasmEngine.DeployListener() {
            @Override
            public void onDeployStatus(String status) {
                lastDeployStatus.set(status);
                System.out.println("[rime] deploy status: " + status);
            }
        });
        long tCreate = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        engine.start("luna_pinyin", "朏月拼音", 10);
        long tStart = System.currentTimeMillis() - t0;
        System.out.println("[rime] create: " + tCreate + " ms, start: " + tStart + " ms");
    }

    @AfterClass
    public static void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @org.junit.Before
    public void clearComposition() {
        // rime state persists across keys; each test starts from a clean session.
        // Non-printable keys use the "{keysym}" form, like the my_rime panel.
        for (int i = 0; i < 30; i++) {
            RimeWasmEngine.Result r = engine.processKey("{Escape}");
            if (r.state == RimeWasmEngine.STATE_UNHANDLED
                    && (r.committed == null || r.committed.isEmpty())
                    && r.preedit().isEmpty()) {
                return;
            }
        }
    }

    @Test
    public void typingNihaoYieldsCandidate() {
        RimeWasmEngine.Result r = type("nihao");
        assertEquals(RimeWasmEngine.STATE_ACCEPTED, r.state);
        assertTrue("preedit should contain pinyin: " + r.preedit(),
                r.preedit().contains("ni hao"));
        assertTrue("expected candidates, got: " + r.candidates.size(),
                r.candidates.size() >= 3);
        assertTrue("first candidate missing 你: " + r.candidates.get(0).text,
                r.candidates.get(0).text.contains("你"));
    }

    @Test
    public void spaceCommitsFirstCandidate() {
        type("nihao");
        RimeWasmEngine.Result r = engine.processKey(" ");
        assertEquals(RimeWasmEngine.STATE_COMMITTED, r.state);
        assertNotNull(r.committed);
        assertTrue("committed should contain 你: " + r.committed,
                r.committed.contains("你"));
    }

    @Test
    public void digitSelectsCandidate() {
        type("ma");
        RimeWasmEngine.Result r = engine.processKey("2");
        assertEquals(RimeWasmEngine.STATE_COMMITTED, r.state);
        assertNotNull(r.committed);
        assertTrue(!r.committed.isEmpty());
    }

    @Test
    public void selectCandidateByIndexWorks() {
        type("ma");
        RimeWasmEngine.Result r = engine.selectCandidateOnCurrentPage(1);
        // single-syllable selection either commits directly or trims the
        // composition; both mean the tap reached the engine
        assertTrue("state should be COMMITTED or ACCEPTED, got " + r.state,
                r.state == RimeWasmEngine.STATE_COMMITTED
                        || r.state == RimeWasmEngine.STATE_ACCEPTED);
        assertTrue("candidates should refresh or text commit",
                r.committed != null || r.candidates.size() > 0);
    }

    @Test
    public void backspaceEditsComposition() {
        type("nihao");
        RimeWasmEngine.Result r = engine.processKey("{BackSpace}");
        assertEquals(RimeWasmEngine.STATE_ACCEPTED, r.state);
        assertTrue("preedit after backspace: " + r.preedit(),
                r.preedit().contains("niha") || r.preedit().contains("ni ha"));
    }

    @Test
    public void changePageFlipsCandidatePages() {
        type("shi");
        RimeWasmEngine.Result first = engine.processKey(" ");
        // "shi" alone may commit directly; use a longer syllable to be safe
        if (first.state == RimeWasmEngine.STATE_COMMITTED) {
            type("mama");
            first = engine.processKey(" ");
        }
        RimeWasmEngine.Result r = type("de");
        if (r.state == RimeWasmEngine.STATE_ACCEPTED && !r.isLastPage) {
            RimeWasmEngine.Result next = engine.changePage(false);
            assertEquals(RimeWasmEngine.STATE_ACCEPTED, next.state);
            assertTrue(next.candidates.size() > 0);
        }
    }

    @Test
    public void userDbFilesWereCreatedUnderRimeDir() {
        File rimeDir = new File(rootDir, "rime");
        File[] files = rimeDir.listFiles();
        assertNotNull(files);
        assertTrue("expected userdb files under rime/: " + java.util.Arrays.toString(files),
                files.length > 0);
    }

    // ------------------------------------------------------------------

    /** types each character as its own keypress, like the my_rime editor */
    private static RimeWasmEngine.Result type(String s) {
        RimeWasmEngine.Result last = null;
        for (int i = 0; i < s.length(); i++) {
            last = engine.processKey(String.valueOf(s.charAt(i)));
        }
        return last;
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
