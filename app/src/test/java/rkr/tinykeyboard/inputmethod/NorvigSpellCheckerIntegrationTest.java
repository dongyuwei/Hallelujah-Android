package rkr.tinykeyboard.inputmethod;

import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs {@link NorvigSpellChecker} against the real bundled words database
 * through JDBC, mirroring what DictionaryDb does on device.
 */
public class NorvigSpellCheckerIntegrationTest {

    private NorvigSpellChecker spellChecker;

    @Before
    public void setUp() throws Exception {
        Connection db = DriverManager.getConnection(
                "jdbc:sqlite:" + TestAssets.copiedAsset("words_with_frequency_and_translation_and_ipa.sqlite3")
                        .getAbsolutePath());
        spellChecker = new NorvigSpellChecker(words -> {
            try {
                return queryFrequencies(db, words);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    static Map<String, Long> queryFrequencies(Connection db, List<String> words) throws Exception {
        if (words.isEmpty()) {
            return Collections.emptyMap();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
        }
        Map<String, Long> frequencies = new HashMap<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT word, frequency FROM words WHERE word IN (" + placeholders + ")")) {
            for (int i = 0; i < words.size(); i++) {
                ps.setString(i + 1, words.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    frequencies.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return frequencies;
    }

    @Test
    public void correctsSingleCharacterTypos_withRealDictionary() {
        // The classic Norvig example: transposed letters in a short word.
        List<String> suggestions = spellChecker.getSuggestions("teh");

        assertTrue("expected 'the' in " + suggestions, suggestions.contains("the"));
        assertEquals("the", suggestions.get(0)); // by far the most frequent edit
    }

    @Test
    public void correctsMissingLetter_inLongerWord() {
        List<String> suggestions = spellChecker.getSuggestions("extendd");

        assertTrue("expected 'extend' or 'extended' in " + suggestions,
                suggestions.contains("extend") || suggestions.contains("extended"));
        assertTrue(suggestions.size() <= NorvigSpellChecker.MAX_SUGGESTIONS);
    }

    @Test
    public void nonWordInputWithNoCloseWords_returnsEmpty() {
        assertTrue(spellChecker.getSuggestions("qwrtzxpbvj").isEmpty());
    }

    @Test
    public void correctsMissingMiddleLetter() {
        // "estmate" is one deletion away from "estimate".
        List<String> suggestions = spellChecker.getSuggestions("estmate");

        assertTrue("expected 'estimate' in " + suggestions, suggestions.contains("estimate"));
        assertTrue(suggestions.size() <= NorvigSpellChecker.MAX_SUGGESTIONS);
    }
}
