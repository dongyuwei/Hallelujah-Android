package rkr.tinykeyboard.inputmethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Spellcheck layer covering up to {@value #MAX_EDIT_DISTANCE} Levenshtein
 * edits via the vocabulary {@link WordTrie} — this is the layer that reaches
 * double and triple typos, which one-edit generation ({@link NorvigSpellChecker})
 * cannot. Candidates are ranked by distance first, then corpus frequency.
 */
public class TrieSpellChecker implements CandidateProvider.SpellCheck {
    static final int MAX_SUGGESTIONS = 8;
    static final int MAX_EDIT_DISTANCE = 3;

    private final Supplier<WordTrie> trieSupplier;
    private final WordFrequencyLookup frequencyLookup;

    public TrieSpellChecker(Supplier<WordTrie> trieSupplier, WordFrequencyLookup frequencyLookup) {
        this.trieSupplier = trieSupplier;
        this.frequencyLookup = frequencyLookup;
    }

    @Override
    public List<String> getSuggestions(String input) {
        List<String> suggestions = new ArrayList<>();
        WordTrie trie = trieSupplier.get();
        if (input == null || input.isEmpty() || trie == null) {
            return suggestions;
        }
        Map<String, Integer> matches = trie.searchWithinDistance(input, MAX_EDIT_DISTANCE);
        if (matches.isEmpty()) {
            return suggestions;
        }
        Map<String, Long> frequencies = frequencyLookup.getFrequencies(new ArrayList<>(matches.keySet()));
        List<Match> ranked = new ArrayList<>(matches.size());
        for (Map.Entry<String, Integer> match : matches.entrySet()) {
            Long frequency = frequencies.get(match.getKey());
            ranked.add(new Match(match.getKey(), match.getValue(), frequency == null ? 0L : frequency));
        }
        ranked.sort(Match::compareTo);
        for (Match match : ranked) {
            if (suggestions.size() == MAX_SUGGESTIONS) {
                break;
            }
            suggestions.add(match.word);
        }
        return suggestions;
    }

    private static final class Match implements Comparable<Match> {
        final String word;
        final int distance;
        final long frequency;

        Match(String word, int distance, long frequency) {
            this.word = word;
            this.distance = distance;
            this.frequency = frequency;
        }

        @Override
        public int compareTo(Match other) {
            int byDistance = Integer.compare(distance, other.distance);
            return byDistance != 0 ? byDistance : Long.compare(other.frequency, frequency);
        }
    }
}
