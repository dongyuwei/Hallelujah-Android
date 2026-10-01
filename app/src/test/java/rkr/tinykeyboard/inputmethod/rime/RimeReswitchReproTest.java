package rkr.tinykeyboard.inputmethod.rime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import rkr.tinykeyboard.inputmethod.TestAssets;

/**
 * Reproduces the on-device "second switch to pinyin is dead" report at the
 * engine level, for the three switch-away cases the app exercises via
 * abandonPinyinComposition (which sends {Escape} on every mode switch):
 *
 * 1. mid-composition, switch away ({Escape}), switch back, type again
 * 2. committed sentence, switch away, switch back, type again
 * 3. composition left UN-committed (no space), switch away, switch back
 */
public class RimeReswitchReproTest {

    private static RimeWasmEngine engine;

    @BeforeClass
    public static void setUp() throws Exception {
        File rootDir = Files.createTempDirectory("rime-reswitch").toFile();
        try (InputStream pack = new FileInputStream(TestAssets.asset("rime/rime.data"))) {
            String manifest = RimeDataPack.readUtf8(
                    new FileInputStream(TestAssets.asset("rime/rime-data-files.txt")));
            RimeDataPack.unpack(rootDir, pack, RimeDataPack.parseManifest(manifest));
        }
        File buildDir = new File(rootDir, "usr/share/rime-data/build");
        for (File f : TestAssets.asset("rime/luna-pinyin").listFiles()) {
            copy(f, new File(buildDir, f.getName()));
        }
        new File(rootDir, "rime").mkdirs();

        byte[] wasm = readAll(new FileInputStream(TestAssets.asset("rime/rime.wasm")));
        engine = RimeWasmEngine.create(rootDir, wasm, null);
        engine.start("luna_pinyin_fluency", "朙月拼音·語句流", 10);
        engine.setOption("simplification", true);
    }

    @AfterClass
    public static void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    public void hostActivityPerKey() {
        // warm up: table/prism lazy-load happens on first keys
        type("nihao");
        engine.processKey(" ");
        engine.processKey(" ");
        // measure a steady-state key
        for (int i = 0; i < 3; i++) {
            long stdio0 = engine.vfsForTest().stdioBytes;
            long lines0 = engine.vfsForTest().stdioLines;
            long t0 = System.nanoTime();
            type("m");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("PERF key=m " + ms + "ms stdioBytes+"
                    + (engine.vfsForTest().stdioBytes - stdio0)
                    + " stdioLines+" + (engine.vfsForTest().stdioLines - lines0));
            engine.processKey("{BackSpace}");
        }
        engine.processKey("{Escape}");
    }

    /** case 1: 组合中切走 → Escape → 再切回 */
    @Test
    public void reswitchAfterAbandoningMidComposition() {
        RimeWasmEngine.Result r = session("ni", "hao");
        assertAlive(r, "mid-composition abandon");
    }

    /** case 2: 拼音 commit 后切走 → 再切回 */
    @Test
    public void reswitchAfterCommittedSentence() {
        type("nihao");
        engine.processKey(" "); // confirm segment
        RimeWasmEngine.Result done = engine.processKey(" "); // commit
        dump("case2 commit", done);
        assertEquals(RimeWasmEngine.STATE_COMMITTED, done.state);
        assertTrue("simplified commit expected: " + done.committed,
                done.committed.contains("你好"));

        // switch away and back
        engine.processKey("{Escape}");

        RimeWasmEngine.Result r = session("jin", "tian");
        assertAlive(r, "post-commit abandon");
    }

    /** case 3: 拼音不 commit（组合还挂着）切走 → 再切回 */
    @Test
    public void reswitchAfterLeavingCompositionUncommitted() {
        type("nihao");
        // no commit, no segment-confirm: the composition is still live and
        // shown as pinyin when the user switches away
        engine.processKey("{Escape}");

        RimeWasmEngine.Result r = session("ma", "ma");
        assertAlive(r, "uncommitted abandon");
    }

    /**
     * One full switch cycle: first half typed (composing), Escape (switch
     * away), second half typed after "switching back".
     */
    private RimeWasmEngine.Result session(String firstHalf, String secondHalf) {
        type(firstHalf);
        RimeWasmEngine.Result abandon = engine.processKey("{Escape}");
        dump("switch-away", abandon);
        return type(secondHalf);
    }

    private static void assertAlive(RimeWasmEngine.Result r, String label) {
        dump(label, r);
        assertTrue("[" + label + "] keys should compose after re-switch, got state="
                + r.state + " preedit=[" + r.preedit() + "]",
                r.state == RimeWasmEngine.STATE_ACCEPTED
                        || r.state == RimeWasmEngine.STATE_COMMITTED);
    }

    private static void dump(String label, RimeWasmEngine.Result r) {
        System.out.println("[" + label + "] state=" + r.state + " preedit=["
                + r.preedit() + "] committed=" + r.committed + " cand="
                + r.candidates.size());
    }

    private static RimeWasmEngine.Result type(String s) {
        RimeWasmEngine.Result last = null;
        for (int i = 0; i < s.length(); i++) {
            last = engine.processKey(String.valueOf(s.charAt(i)));
        }
        return last;
    }

    private static void copy(File from, File to) throws Exception {
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

    private static byte[] readAll(InputStream in) throws Exception {
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
