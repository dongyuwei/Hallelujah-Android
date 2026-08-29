package rkr.tinykeyboard.inputmethod;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CandidateProviderTest {

    private static class StubDictionary implements CandidateProvider.Dictionary {
        final Map<String, List<String>> english = new HashMap<>();
        final Map<String, List<String>> pinyin = new HashMap<>();
        String lastEnglishPrefix;
        String lastPinyinPrefix;
        int lastEnglishLimit = -1;
        int lastPinyinLimit = -1;

        @Override
        public List<String> getEnglishWords(String prefix, int limit) {
            lastEnglishPrefix = prefix;
            lastEnglishLimit = limit;
            return new ArrayList<>(english.getOrDefault(prefix, new ArrayList<>()));
        }

        @Override
        public List<String> getHanZiByPinyin(String prefix, int limit) {
            lastPinyinPrefix = prefix;
            lastPinyinLimit = limit;
            return new ArrayList<>(pinyin.getOrDefault(prefix, new ArrayList<>()));
        }
    }

    private StubDictionary dictionary = new StubDictionary();
    private final Map<String, List<String>> cedict = new HashMap<>();
    private CandidateProvider provider = new CandidateProvider(dictionary, cedict);

    @Test
    public void englishMode_typesPrefixFirst_thenDictionaryWords() {
        dictionary.english.put("pa", Arrays.asList("page", "part", "past"));

        List<String> candidates = provider.getCandidates("pa", InputMode.English);

        assertEquals(Arrays.asList("pa", "page", "part", "past"), candidates);
    }

    @Test
    public void englishMode_fallsBackToCedict_whenNoWordMatches() {
        cedict.put("pa", Arrays.asList("怕", "to be afraid"));

        List<String> candidates = provider.getCandidates("pa", InputMode.English);

        assertEquals(Arrays.asList("pa", "怕", "to be afraid"), candidates);
    }

    @Test
    public void englishMode_prefersDictionaryOverCedict() {
        dictionary.english.put("pa", Arrays.asList("page"));
        cedict.put("pa", Arrays.asList("怕"));

        List<String> candidates = provider.getCandidates("pa", InputMode.English);

        assertFalse(candidates.contains("怕"));
    }

    @Test
    public void englishMode_queriesLowercasedPrefix_withRoomForPrefix() {
        dictionary.english.put("pa", Arrays.asList("page"));

        provider.getCandidates("PA", InputMode.English);

        assertEquals("pa", dictionary.lastEnglishPrefix);
        assertEquals(CandidateProvider.MAX_CANDIDATES - 1, dictionary.lastEnglishLimit);
        assertTrue(provider.getCandidates("PA", InputMode.English).get(0).equals("pa"));
    }

    @Test
    public void pinyinMode_delegatesToHanziLookup_withoutTypedPrefixFirst() {
        dictionary.pinyin.put("xhs", Arrays.asList("西红柿"));

        List<String> candidates = provider.getCandidates("xhs", InputMode.Pinyin);

        assertEquals(Arrays.asList("西红柿"), candidates);
        assertEquals("xhs", dictionary.lastPinyinPrefix);
        assertEquals(CandidateProvider.MAX_CANDIDATES, dictionary.lastPinyinLimit);
    }

    @Test
    public void englishMode_noDictMatch_cedictFirstThenSpellLayersInOrder() {
        cedict.put("xihongshi", Arrays.asList("西红柿"));
        CandidateProvider.SpellCheck editDistanceLayer = input -> Arrays.asList("something");
        CandidateProvider.SpellCheck phonexLayer = input -> Arrays.asList("sometimes");
        CandidateProvider providerWithSpellCheck =
                new CandidateProvider(dictionary, cedict, editDistanceLayer, phonexLayer);

        List<String> candidates = providerWithSpellCheck.getCandidates("xihongshi", InputMode.English);

        assertEquals(Arrays.asList("xihongshi", "西红柿", "something", "sometimes"), candidates);
    }

    @Test
    public void englishMode_noDictMatch_cedictFirstThenSpellSuggestions() {
        cedict.put("xihongshi", Arrays.asList("西红柿"));
        CandidateProvider.SpellCheck spellCheck = input -> Arrays.asList("something", "sometimes");
        CandidateProvider providerWithSpellCheck = new CandidateProvider(dictionary, cedict, spellCheck);

        List<String> candidates = providerWithSpellCheck.getCandidates("xihongshi", InputMode.English);

        assertEquals(Arrays.asList("xihongshi", "西红柿", "something", "sometimes"), candidates);
    }

    @Test
    public void englishMode_spellSuggestions_whenNoCedictHitEither() {
        CandidateProvider.SpellCheck spellCheck = input -> Arrays.asList("extended", "extent");
        CandidateProvider providerWithSpellCheck = new CandidateProvider(dictionary, cedict, spellCheck);

        List<String> candidates = providerWithSpellCheck.getCandidates("extendd", InputMode.English);

        assertEquals(Arrays.asList("extendd", "extended", "extent"), candidates);
    }

    @Test
    public void englishMode_dictionaryMatch_skipsSpellSuggestions() {
        dictionary.english.put("pa", Arrays.asList("page"));
        CandidateProvider.SpellCheck spellCheck = input -> {
            throw new AssertionError("spellcheck must not run when words match");
        };
        CandidateProvider providerWithSpellCheck = new CandidateProvider(dictionary, cedict, spellCheck);

        assertEquals(Arrays.asList("pa", "page"), providerWithSpellCheck.getCandidates("pa", InputMode.English));
    }

    @Test
    public void emptyComposition_returnsNoCandidates() {
        assertEquals(new ArrayList<>(), provider.getCandidates("", InputMode.English));
        assertEquals(new ArrayList<>(), provider.getCandidates("", InputMode.Pinyin));
        assertEquals(new ArrayList<>(), provider.getDisplayCandidates("", InputMode.English));
    }

    @Test
    public void displayCandidates_areCappedAtLimit() {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            words.add("word" + i);
        }
        dictionary.english.put("w", words);

        List<String> display = provider.getDisplayCandidates("w", InputMode.English);

        assertEquals(CandidateProvider.MAX_CANDIDATES, display.size());
        assertEquals("w", display.get(0));
        assertFalse(display.contains("word20")); // beyond the cap
    }

    @Test
    public void displayCandidates_areDeduplicated_keepingFirstOccurrence() {
        dictionary.english.put("w", Arrays.asList("word0", "word1", "word0", "word2"));

        List<String> display = provider.getDisplayCandidates("w", InputMode.English);

        assertEquals(Arrays.asList("w", "word0", "word1", "word2"), display);
    }
}
