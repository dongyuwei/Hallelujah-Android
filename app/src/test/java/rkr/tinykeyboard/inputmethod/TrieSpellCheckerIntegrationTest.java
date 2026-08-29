package rkr.tinykeyboard.inputmethod;

import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * Builds the {@link WordTrie} from the real bundled words database (like
 * DictionaryDb does on device) and checks the distance-3 spell layer end to
 * end through JDBC.
 */
public class TrieSpellCheckerIntegrationTest {

    private static TrieSpellChecker checker;

    @BeforeClass
    public static void buildTrieFromRealDictionary() throws Exception {
        Connection db = DriverManager.getConnection(
                "jdbc:sqlite:" + TestAssets.copiedAsset("words_with_frequency_and_translation_and_ipa.sqlite3")
                        .getAbsolutePath());
        List<String> words = new ArrayList<>();
        try (Statement statement = db.createStatement();
             ResultSet rs = statement.executeQuery("SELECT word FROM words")) {
            while (rs.next()) {
                words.add(rs.getString(1));
            }
        }
        WordTrie trie = new WordTrie();
        for (String word : words) {
            trie.insert(word);
        }
        System.out.printf("WordTrie built: %d words, %d nodes (%.1f MB)%n",
                words.size(), trie.getNodeCount(), trie.getNodeCount() * 32.0 / 1024 / 1024);
        checker = new TrieSpellChecker(() -> trie, words1 -> {
            try {
                return NorvigSpellCheckerIntegrationTest.queryFrequencies(db, words1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    public void correctsTwoEditTypos() {
        // "estmte" is two edits from "estimate" (insert i, insert a).
        List<String> suggestions = checker.getSuggestions("estmte");
        assertTrue("expected 'estimate' in " + suggestions, suggestions.contains("estimate"));
    }

    @Test
    public void correctsThreeEditTypos() {
        // "estmotx" is three single edits away from "estimate"
        // (delete i, substitute a->o, substitute e->x); far beyond one-edit search.
        List<String> suggestions = checker.getSuggestions("estmotx");
        assertTrue("expected 'estimate' in " + suggestions, suggestions.contains("estimate"));
    }

    @Test
    public void noMatchesForGibberish() {
        assertTrue(checker.getSuggestions("qxvxqzx").isEmpty());
    }

    @Test
    public void suggestionsStayWithinCap() {
        List<String> suggestions = checker.getSuggestions("expendd");
        assertTrue(suggestions.size() > 0);
        assertTrue(suggestions.size() <= TrieSpellChecker.MAX_SUGGESTIONS);
    }
}
