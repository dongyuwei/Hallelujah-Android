package rkr.tinykeyboard.inputmethod;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Runs the production SQL statements from {@link DictionaryDb} against the
 * real bundled .sqlite3 assets (copied to a temp dir first, like the app does
 * on device), so query results and dictionary contents are covered without
 * an Android device.
 */
public class DictionarySqlTest {

    private static Path tempDir;
    private static Connection englishDb;
    private static Connection pinyinDb;

    @BeforeClass
    public static void openDatabases() throws Exception {
        tempDir = Files.createTempDirectory("hallelujah-dict-test");
        englishDb = openCopiedAsset("words_with_frequency_and_translation_and_ipa.sqlite3");
        pinyinDb = openCopiedAsset("pinyin_data.sqlite3");
        // Same pragma DictionaryDb.openDatabase() sets on device.
        try (Statement s = englishDb.createStatement()) {
            s.execute("PRAGMA case_sensitive_like = ON");
        }
        try (Statement s = pinyinDb.createStatement()) {
            s.execute("PRAGMA case_sensitive_like = ON");
        }
    }

    @AfterClass
    public static void closeDatabases() throws Exception {
        for (Connection db : new Connection[]{englishDb, pinyinDb}) {
            if (db != null) {
                db.close();
            }
        }
        if (tempDir != null) {
            try (Stream<Path> files = Files.list(tempDir)) {
                files.forEach(f -> f.toFile().delete());
            } catch (Exception ignored) {
            }
            tempDir.toFile().delete();
        }
    }

    private static Connection openCopiedAsset(String asset) throws Exception {
        File source = assetFile(asset);
        assertTrue("Missing bundled asset: " + source, source.exists());
        File target = tempDir.resolve(asset).toFile();
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return DriverManager.getConnection("jdbc:sqlite:" + target.getAbsolutePath());
    }

    /** Test workers may run with either the module dir or the repo root as working dir. */
    private static File assetFile(String name) {
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        Path candidate = workingDir.resolve("src/main/assets").resolve(name);
        if (!Files.exists(candidate)) {
            candidate = workingDir.resolve("app/src/main/assets").resolve(name);
        }
        return candidate.toFile();
    }

    private static List<String> query(Connection db, String sql, Object[] args) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<String> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(rs.getString(1));
                }
                return results;
            }
        }
    }

    private static List<String> englishWords(String prefix, int limit) throws Exception {
        return query(englishDb, DictionaryDb.ENGLISH_WORDS_SQL + " LIMIT " + limit,
                new Object[]{prefix + "%"});
    }

    private static List<String> hanZiByPinyin(String prefix, int limit) throws Exception {
        return query(pinyinDb, DictionaryDb.HANZI_BY_PINYIN_SQL + " LIMIT " + limit,
                new Object[]{prefix + "%", prefix + "%", prefix, prefix});
    }

    @Test
    public void englishQuery_returnsTopWordsByFrequency() throws Exception {
        List<String> words = englishWords("pa", 19);

        assertEquals("page", words.get(0));
        assertTrue(words.containsAll(Arrays.asList("part", "pages", "party")));
        assertEquals(19, words.size());
    }

    @Test
    public void englishQuery_respectsLimit() throws Exception {
        assertEquals(3, englishWords("pa", 3).size());
    }

    @Test
    public void englishQuery_isCaseSensitive_likeTheProductionPragma() throws Exception {
        // Prefixes are always lowercased before lookup; with case_sensitive_like
        // ON, an uppercase pattern must not silently match lowercase words.
        assertTrue(englishWords("pa", 19).size() > 0);
        assertTrue(englishWords("PA", 19).isEmpty());
    }

    @Test
    public void pinyinQuery_exactFullPinyinMatchComesFirst() throws Exception {
        List<String> hanzi = hanZiByPinyin("xihongshi", 20);

        assertEquals("西红柿", hanzi.get(0));
        assertEquals(1, hanzi.stream().filter("西红柿"::equals).count());
    }

    @Test
    public void pinyinQuery_abbrMatchFindsTheSameWord() throws Exception {
        assertTrue(hanZiByPinyin("xhs", 20).contains("西红柿"));
    }

    @Test
    public void pinyinQuery_ordersByFrequency_forExactSingleSyllable() throws Exception {
        assertEquals("怕", hanZiByPinyin("pa", 20).get(0));
    }

    @Test
    public void pinyinQuery_respectsLimit() throws Exception {
        List<String> hanzi = hanZiByPinyin("x", 20);
        assertEquals(20, hanzi.size());
        assertEquals(hanzi.size(), new ArrayList<>(hanzi).stream().distinct().count());
    }

    @Test
    public void bundledDictionaries_matchDesktopVersions() throws Exception {
        try (ResultSet rs = englishDb.createStatement().executeQuery("SELECT COUNT(*) FROM words");
             ResultSet rs2 = pinyinDb.createStatement().executeQuery("SELECT COUNT(*) FROM pinyin_data")) {
            assertTrue(rs.next());
            assertTrue(rs2.next());
            assertTrue("words table looks wrong: " + rs.getInt(1), rs.getInt(1) >= 140000);
            assertTrue("pinyin_data table looks wrong: " + rs2.getInt(1), rs2.getInt(1) >= 55000);
        }
    }

    @Test
    public void pinyinQuery_neverReturnsEmptyHanzi() throws Exception {
        assertFalse(hanZiByPinyin("b", 20).contains(""));
    }
}
