package rkr.tinykeyboard.inputmethod;

import java.util.List;
import java.util.Map;

/** Looks up corpus frequencies for existing dictionary words. */
public interface WordFrequencyLookup {
    Map<String, Long> getFrequencies(List<String> words);
}
