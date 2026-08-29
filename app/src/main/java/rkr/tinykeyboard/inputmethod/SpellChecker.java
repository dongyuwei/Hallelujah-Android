package rkr.tinykeyboard.inputmethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fuzzy English spelling suggestions, mirroring the phonetic half of
 * hallelujahIM's getSuggestionOfSpellChecker: look up the words whose Phonex
 * code matches the input's, rank them by edit distance to the input, and keep
 * the closest few. The map comes from the bundled phonex_encoded_words.json
 * (buckets are pre-sorted by descending corpus frequency, so stable sorting
 * keeps more frequent words first among equal distances).
 */
public class SpellChecker implements CandidateProvider.SpellCheck {
    static final int MAX_SUGGESTIONS = 8;
    static final int MAX_EDIT_DISTANCE = 3;
    // hallelujahIM only encodes/matches words longer than 3 characters.
    static final int MIN_INPUT_LENGTH = 4;

    private final Map<String, List<String>> phonexMap;

    public SpellChecker(Map<String, List<String>> phonexMap) {
        this.phonexMap = phonexMap;
    }

    @Override
    public List<String> getSuggestions(String input) {
        List<String> suggestions = new ArrayList<>();
        if (input == null || input.length() < MIN_INPUT_LENGTH) {
            return suggestions;
        }
        List<String> phoneticWords = phonexMap.get(Phonex.encode(input));
        if (phoneticWords == null) {
            return suggestions;
        }
        List<WordDistance> ranked = new ArrayList<>();
        for (String word : phoneticWords) {
            int distance = EditDistance.distance(input, word);
            if (distance <= MAX_EDIT_DISTANCE) {
                ranked.add(new WordDistance(word, distance));
            }
        }
        Collections.sort(ranked);
        for (WordDistance candidate : ranked) {
            if (suggestions.size() == MAX_SUGGESTIONS) {
                break;
            }
            suggestions.add(candidate.word);
        }
        return suggestions;
    }

    private static class WordDistance implements Comparable<WordDistance> {
        final String word;
        final int distance;

        WordDistance(String word, int distance) {
            this.word = word;
            this.distance = distance;
        }

        @Override
        public int compareTo(WordDistance other) {
            return Integer.compare(distance, other.distance);
        }
    }
}
