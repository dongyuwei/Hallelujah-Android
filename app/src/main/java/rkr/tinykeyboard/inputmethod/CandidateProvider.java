package rkr.tinykeyboard.inputmethod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the candidate list shown above the keyboard. Pure Java so the
 * ranking, cedict fallback and de-duplication rules can be unit tested
 * without an Android device.
 */
public class CandidateProvider {

    /** Dictionary lookups backing English candidate generation (SQLite on device). */
    public interface Dictionary {
        List<String> getEnglishWords(String prefix, int limit);
    }

    /** Fuzzy spelling suggestions for the English-mode fallback path. */
    public interface SpellCheck {
        List<String> getSuggestions(String input);
    }

    public static final int MAX_CANDIDATES = 20;

    private final Dictionary dictionary;
    private final Map<String, List<String>> pinyinMap;
    private final SpellCheck[] spellChecks;

    /**
     * @param pinyinMap cedict entries used in English mode when no English word matches
     */
    public CandidateProvider(Dictionary dictionary, Map<String, List<String>> pinyinMap) {
        this(dictionary, pinyinMap, new SpellCheck[0]);
    }

    /** @param spellChecks suggestion layers applied in order after the cedict fallback */
    public CandidateProvider(Dictionary dictionary, Map<String, List<String>> pinyinMap, SpellCheck... spellChecks) {
        this.dictionary = dictionary;
        this.pinyinMap = pinyinMap;
        this.spellChecks = spellChecks;
    }

    /** Candidates for display: ranked, capped at {@link #MAX_CANDIDATES} and de-duplicated. */
    public List<String> getDisplayCandidates(String composition, InputMode mode) {
        List<String> candidates = getCandidates(composition, mode);
        candidates = candidates.subList(0, Math.min(candidates.size(), MAX_CANDIDATES));
        return withoutDuplicates(candidates);
    }

    List<String> getCandidates(String composition, InputMode mode) {
        List<String> candidates = new ArrayList<>();
        if (composition == null || composition.isEmpty()) {
            return candidates;
        }
        String prefix = composition.toLowerCase();
        if (mode != InputMode.English) {
            // Pinyin candidates come from librime (RimeEngine), not here.
            return candidates;
        }
        // The typed prefix itself is always the first candidate.
        candidates.add(prefix);
        List<String> matchingWords = dictionary.getEnglishWords(prefix, MAX_CANDIDATES - 1);
        if (!matchingWords.isEmpty()) {
            candidates.addAll(matchingWords);
        } else {
            // No English word matches: cedict pinyin fallback first, then
            // the spellcheck layers (edit-distance fixes, phonetic guesses)
            // for possible typos.
            List<String> pinyinCandidates = pinyinMap.get(prefix);
            if (pinyinCandidates != null) {
                candidates.addAll(pinyinCandidates);
            }
            for (SpellCheck checker : spellChecks) {
                candidates.addAll(checker.getSuggestions(prefix));
            }
        }
        return candidates;
    }

    private List<String> withoutDuplicates(List<String> candidates) {
        Set<String> setWithoutDuplicates = new LinkedHashSet<>(candidates);
        return new ArrayList<>(setWithoutDuplicates);
    }
}
