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

    /** Dictionary lookups backing candidate generation (backed by SQLite on device). */
    public interface Dictionary {
        List<String> getEnglishWords(String prefix, int limit);

        List<String> getHanZiByPinyin(String prefix, int limit);
    }

    public static final int MAX_CANDIDATES = 20;

    private final Dictionary dictionary;
    private final Map<String, List<String>> pinyinMap;

    /**
     * @param pinyinMap cedict entries used in English mode when no English word matches
     */
    public CandidateProvider(Dictionary dictionary, Map<String, List<String>> pinyinMap) {
        this.dictionary = dictionary;
        this.pinyinMap = pinyinMap;
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
        if (mode == InputMode.English) {
            // The typed prefix itself is always the first candidate.
            candidates.add(prefix);
            List<String> matchingWords = dictionary.getEnglishWords(prefix, MAX_CANDIDATES - 1);
            if (!matchingWords.isEmpty()) {
                candidates.addAll(matchingWords);
            } else if (pinyinMap.containsKey(prefix)) {
                candidates.addAll(pinyinMap.get(prefix));
            }
        } else {
            candidates.addAll(dictionary.getHanZiByPinyin(prefix, MAX_CANDIDATES));
        }
        return candidates;
    }

    private List<String> withoutDuplicates(List<String> candidates) {
        Set<String> setWithoutDuplicates = new LinkedHashSet<>(candidates);
        return new ArrayList<>(setWithoutDuplicates);
    }
}
