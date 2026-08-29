package rkr.tinykeyboard.inputmethod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Norvig-style spelling correction (see
 * https://norvig.com/spell-correct.html): generate every string one edit away
 * from the typed word (deletions, transpositions, replacements, insertions)
 * and keep the ones that are real dictionary words, ranked by corpus
 * frequency. Covers single-edit typos at any word length; deeper corrections
 * are handled by {@link TrieSpellChecker} (distance up to 3) and phonetic
 * guesses by {@link SpellChecker} (Phonex).
 */
public class NorvigSpellChecker implements CandidateProvider.SpellCheck {
    static final int MAX_SUGGESTIONS = 8;

    private static final char[] LETTERS = "abcdefghijklmnopqrstuvwxyz".toCharArray();

    private final WordFrequencyLookup frequencyLookup;

    public NorvigSpellChecker(WordFrequencyLookup frequencyLookup) {
        this.frequencyLookup = frequencyLookup;
    }

    @Override
    public List<String> getSuggestions(String input) {
        List<String> suggestions = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return suggestions;
        }
        Map<String, Long> knownWords = frequencyLookup.getFrequencies(edits1(input));
        List<Map.Entry<String, Long>> ranked = new ArrayList<>(knownWords.entrySet());
        ranked.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        for (Map.Entry<String, Long> entry : ranked) {
            if (suggestions.size() == MAX_SUGGESTIONS) {
                break;
            }
            suggestions.add(entry.getKey());
        }
        return suggestions;
    }

    /** All strings within one edit (delete/transpose/replace/insert) of {@code word}. */
    static List<String> edits1(String word) {
        Set<String> edits = new LinkedHashSet<>();
        int length = word.length();
        for (int i = 0; i <= length; i++) {
            String head = word.substring(0, i);
            if (i < length) {
                edits.add(head + word.substring(i + 1)); // deletion
            }
            for (char c : LETTERS) {
                edits.add(head + c + word.substring(i)); // insertion
                if (i < length && c != word.charAt(i)) {
                    edits.add(head + c + word.substring(i + 1)); // replacement
                }
            }
            if (i < length - 1) {
                edits.add(head + word.charAt(i + 1) + word.charAt(i) + word.substring(i + 2)); // transposition
            }
        }
        return new ArrayList<>(edits);
    }
}
