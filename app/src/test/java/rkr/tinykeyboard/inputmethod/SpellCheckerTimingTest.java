package rkr.tinykeyboard.inputmethod;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.assertFalse;

/**
 * Prints per-call latencies of the spellcheck layers (not asserted, just
 * printed). Timings are JVM + JDBC ballparks against the bundled words
 * database; on-device SQLite/ART numbers will differ.
 */
public class SpellCheckerTimingTest {

    private static NorvigSpellChecker norvigChecker;
    private static SpellChecker phonexChecker;
    private static Connection wordsDb;

    @BeforeClass
    public static void setUp() throws Exception {
        wordsDb = DriverManager.getConnection(
                "jdbc:sqlite:" + TestAssets.copiedAsset("words_with_frequency_and_translation_and_ipa.sqlite3")
                        .getAbsolutePath());
        norvigChecker = new NorvigSpellChecker(words -> {
            try {
                return NorvigSpellCheckerIntegrationTest.queryFrequencies(wordsDb, words);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        Type type = new TypeToken<Map<String, List<String>>>() {
        }.getType();
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(TestAssets.asset("phonex_encoded_words.json")), StandardCharsets.UTF_8)) {
            Map<String, List<String>> phonexMap = new Gson().fromJson(reader, type);
            phonexChecker = new SpellChecker(phonexMap);
        }
        System.out.println("== Spellcheck timings (JVM + JDBC ballparks, not device numbers) ==");
    }

    @Test
    public void printNorvigLayerTimings() {
        time("norvig 'teh' (3 chars)", 100, 500, () -> norvigChecker.getSuggestions("teh"));
        time("norvig 'extendd' (7 chars)", 50, 300, () -> norvigChecker.getSuggestions("extendd"));
        time("norvig 'estmate' (7 chars)", 50, 300, () -> norvigChecker.getSuggestions("estmate"));
        time("norvig 20-char input (worst case)", 20, 100, () -> norvigChecker.getSuggestions("uncharacteristically"));
        time("norvig no-match input", 20, 100, () -> norvigChecker.getSuggestions("qwrtzxpbvj"));

        // Breakdown for one input: candidate generation vs database lookup.
        time("norvig breakdown: edits1('extendd') only", 50, 300,
                () -> NorvigSpellChecker.edits1("extendd"));
        List<String> edits = NorvigSpellChecker.edits1("extendd");
        time("norvig breakdown: lookup only (" + edits.size() + " words)", 50, 300, () -> {
            try {
                return NorvigSpellCheckerIntegrationTest.queryFrequencies(wordsDb, edits);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    public void printPhonexLayerTimings() {
        time("phonex 'extendd' (7 chars)", 100, 500, () -> phonexChecker.getSuggestions("extendd"));
        time("phonex 'estmate' (7 chars)", 100, 500, () -> phonexChecker.getSuggestions("estmate"));
        time("phonex 20-char input", 50, 300, () -> phonexChecker.getSuggestions("uncharacteristically"));
        time("phonex no-bucket input", 50, 300, () -> phonexChecker.getSuggestions("qwrtzxpbvj"));
    }

    @Test
    public void printTrieLayerTimings() throws Exception {
        long buildStart = System.nanoTime();
        Connection db = wordsDb;
        WordTrie trie = new WordTrie();
        int words = 0;
        try (Statement statement = db.createStatement();
             ResultSet rs = statement.executeQuery("SELECT word FROM words")) {
            while (rs.next()) {
                trie.insert(rs.getString(1));
                words++;
            }
        }
        System.out.printf("trie build: %d words, %d nodes (%.1f MB) in %.0f ms (one-time, loader thread)%n",
                words, trie.getNodeCount(), trie.getNodeCount() * 32.0 / 1024 / 1024,
                (System.nanoTime() - buildStart) / 1e6);

        TrieSpellChecker checker = new TrieSpellChecker(() -> trie, words1 -> {
            try {
                return NorvigSpellCheckerIntegrationTest.queryFrequencies(db, words1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        time("trie search 'extendd' (7 chars)", 20, 200, () -> trie.searchWithinDistance("extendd", 3));
        time("trie search 20-char input", 10, 100, () -> trie.searchWithinDistance("uncharacteristically", 3));
        time("trie search no-match input", 10, 100, () -> trie.searchWithinDistance("qwrtzxpbvj", 3));
        time("trie full layer 'estmate' (covers ed-2)", 20, 200, () -> checker.getSuggestions("estmate"));
        time("trie full layer 'estmotx' (covers ed-3)", 20, 200, () -> checker.getSuggestions("estmotx"));
        time("trie full layer no-match", 10, 100, () -> checker.getSuggestions("qwrtzxpbvj"));
    }

    private static void time(String label, int warmup, int runs, Supplier<?> action) {
        for (int i = 0; i < warmup; i++) {
            action.get();
        }
        long min = Long.MAX_VALUE;
        long max = 0;
        long total = 0;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            Object result = action.get();
            long elapsed = System.nanoTime() - start;
            total += elapsed;
            min = Math.min(min, elapsed);
            max = Math.max(max, elapsed);
            assertFalse(result == null);
        }
        System.out.printf("%-42s avg %8.3f ms | min %8.3f ms | max %8.3f ms (%d runs)%n",
                label, total / 1e6 / runs, min / 1e6, max / 1e6, runs);
    }
}
