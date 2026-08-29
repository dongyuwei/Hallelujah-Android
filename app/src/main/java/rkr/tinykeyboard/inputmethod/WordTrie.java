package rkr.tinykeyboard.inputmethod;

import java.util.HashMap;
import java.util.Map;

/**
 * Trie over the dictionary vocabulary, searchable with a banded Levenshtein
 * dynamic-programming walk (Steve Hanov, "Fast and Easy Levenshtein distance
 * using a Trie"): the DP row travels down the tree and whole subtrees are
 * pruned as soon as their minimum possible distance exceeds the threshold.
 * This makes exact "all words within edit distance N" queries affordable —
 * which pure candidate generation cannot do beyond two edits (~30M+ strings).
 *
 * Plain Levenshtein (no adjacent transposition); single-character swaps are
 * covered by {@link NorvigSpellChecker}'s edit-1 layer, which runs first.
 */
public final class WordTrie {
    private final Node root = new Node();
    private int nodeCount;

    private static final class Node {
        Node firstChild;
        Node sibling;
        char c;
        boolean terminal;
    }

    public void insert(String word) {
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            Node child = node.firstChild;
            Node previousSibling = null;
            while (child != null && child.c != c) {
                previousSibling = child;
                child = child.sibling;
            }
            if (child == null) {
                child = new Node();
                child.c = c;
                nodeCount++;
                if (previousSibling == null) {
                    node.firstChild = child;
                } else {
                    previousSibling.sibling = child;
                }
            }
            node = child;
        }
        node.terminal = true;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    /**
     * @return every inserted word within {@code maxDist} Levenshtein edits of
     * {@code input}, mapped to its distance
     */
    public Map<String, Integer> searchWithinDistance(String input, int maxDist) {
        Map<String, Integer> matches = new HashMap<>();
        int[] row = new int[input.length() + 1];
        for (int j = 0; j <= input.length(); j++) {
            row[j] = j;
        }
        for (Node child = root.firstChild; child != null; child = child.sibling) {
            search(child, input, maxDist, row, new StringBuilder(), matches);
        }
        return matches;
    }

    private void search(Node node, String input, int maxDist, int[] previousRow,
                        StringBuilder prefix, Map<String, Integer> matches) {
        char letter = node.c;
        prefix.append(letter);

        int columns = previousRow.length;
        int[] currentRow = new int[columns];
        currentRow[0] = previousRow[0] + 1;
        int min = currentRow[0];
        for (int j = 1; j < columns; j++) {
            int insertCost = currentRow[j - 1] + 1;
            int deleteCost = previousRow[j] + 1;
            int substituteCost = previousRow[j - 1] + (input.charAt(j - 1) == letter ? 0 : 1);
            currentRow[j] = Math.min(Math.min(insertCost, deleteCost), substituteCost);
            if (currentRow[j] < min) {
                min = currentRow[j];
            }
        }

        if (min <= maxDist) {
            if (node.terminal && currentRow[columns - 1] <= maxDist) {
                matches.put(prefix.toString(), currentRow[columns - 1]);
            }
            for (Node child = node.firstChild; child != null; child = child.sibling) {
                search(child, input, maxDist, currentRow, prefix, matches);
            }
        }

        prefix.deleteCharAt(prefix.length() - 1);
    }
}
