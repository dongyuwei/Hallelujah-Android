package rkr.tinykeyboard.inputmethod;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WordTrieTest {

    private static final List<String> WORDS =
            Arrays.asList("cat", "car", "cart", "cats", "dog", "the", "them");

    private WordTrie trie;

    @Before
    public void buildTrie() {
        trie = new WordTrie();
        for (String word : WORDS) {
            trie.insert(word);
        }
    }

    @Test
    public void search_matchesBruteForceLevenshtein() {
        for (String input : Arrays.asList("ca", "cat", "cate", "xat", "teh", "dsg", "them", "")) {
            for (int maxDist : Arrays.asList(1, 2, 3)) {
                assertEquals("input='" + input + "' maxDist=" + maxDist,
                        bruteForce(input, maxDist), trie.searchWithinDistance(input, maxDist));
            }
        }
    }

    @Test
    public void searchReportsZeroDistanceForExactWord() {
        assertEquals(Integer.valueOf(0), trie.searchWithinDistance("cart", 3).get("cart"));
    }

    @Test
    public void searchNeverExceedsThreshold() {
        for (Map.Entry<String, Integer> match : trie.searchWithinDistance("xatz", 2).entrySet()) {
            assertTrue(match.getValue() <= 2);
        }
    }

    /** Plain Levenshtein (no transposition), matching the trie's DP. */
    private static Map<String, Integer> bruteForce(String input, int maxDist) {
        Map<String, Integer> expected = new HashMap<>();
        for (String word : WORDS) {
            int distance = levenshtein(input, word);
            if (distance <= maxDist) {
                expected.put(word, distance);
            }
        }
        return expected;
    }

    private static int levenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        return d[a.length()][b.length()];
    }
}
