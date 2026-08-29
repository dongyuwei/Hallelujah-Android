package rkr.tinykeyboard.inputmethod;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TrieSpellCheckerTest {

    @Test
    public void ranksByDistanceFirst_thenFrequency() {
        WordTrie trie = new WordTrie();
        trie.insert("aab"); // distance 0 from "aab"
        trie.insert("axx"); // distance 2
        trie.insert("qqq"); // distance 3
        Map<String, Long> frequencies = new HashMap<>();
        frequencies.put("qqq", 1000L); // most frequent, but farthest
        frequencies.put("axx", 100L);
        frequencies.put("aab", 1L);
        TrieSpellChecker checker =
                new TrieSpellChecker(() -> trie, words -> frequencies);

        assertEquals(Arrays.asList("aab", "axx", "qqq"), checker.getSuggestions("aab"));
    }

    @Test
    public void capsSuggestionsAtLimit() {
        WordTrie trie = new WordTrie();
        for (int i = 0; i < 20; i++) {
            trie.insert("word" + i);
        }
        Map<String, Long> frequencies = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            frequencies.put("word" + i, (long) i);
        }
        TrieSpellChecker checker = new TrieSpellChecker(() -> trie, words -> frequencies);

        assertEquals(TrieSpellChecker.MAX_SUGGESTIONS, checker.getSuggestions("word0").size());
    }

    @Test
    public void emptyInput_returnsNoSuggestions() {
        TrieSpellChecker checker =
                new TrieSpellChecker(WordTrie::new, words -> Collections.emptyMap());

        assertTrue(checker.getSuggestions("").isEmpty());
        assertTrue(checker.getSuggestions(null).isEmpty());
    }

    @Test
    public void missingTrie_returnsNoSuggestions() {
        TrieSpellChecker checker =
                new TrieSpellChecker(() -> null, words -> Collections.emptyMap());

        assertTrue(checker.getSuggestions("cat").isEmpty());
    }

    @Test
    public void noMatches_returnsNoSuggestions() {
        WordTrie trie = new WordTrie();
        trie.insert("xyz");
        TrieSpellChecker checker = new TrieSpellChecker(() -> trie, words -> Collections.emptyMap());

        assertTrue(checker.getSuggestions("qqqqqqq").isEmpty());
    }
}
