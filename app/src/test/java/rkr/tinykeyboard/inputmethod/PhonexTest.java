package rkr.tinykeyboard.inputmethod;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PhonexTest {

    @Test
    public void encodes_groundTruthWords_likeTalismanPhonex() {
        // Vectors sampled from the shipped phonex_encoded_words.json, which
        // hallelujahIM built with the original talisman/phonex.js encoder.
        assertEquals("A2353", Phonex.encode("extended"));
        assertEquals("M5123", Phonex.encode("manifesto"));
        assertEquals("F3252", Phonex.encode("photogenic"));
        assertEquals("S354", Phonex.encode("stanley"));
        assertEquals("S16525", Phonex.encode("supremacism"));
        assertEquals("A5262362324", Phonex.encode("uncharacteristically"));
        assertEquals("A52165", Phonex.encode("enciphering"));
        assertEquals("B21452", Phonex.encode("bioequivalence"));
        assertEquals("T65165", Phonex.encode("trimipramine"));
    }

    @Test
    public void handles_edgeCases() {
        assertEquals("", Phonex.encode(null));
        assertEquals("", Phonex.encode(""));
        assertEquals("", Phonex.encode("12345")); // nothing left after stripping
        assertEquals("", Phonex.encode("sss")); // everything stripped as trailing S
        assertEquals("A", Phonex.encode("he")); // leading H dropped, vowel initial
        assertEquals("", Phonex.encode("h")); // leading H dropped -> nothing left
        assertEquals("C6", Phonex.encode("cross")); // trailing S stripped before encoding
        assertEquals("N", Phonex.encode("know")); // KN -> N, trailing W is silent (0)
    }

    @Test
    public void encodes_everyWordInBundledDictionary_toItsBucketKey() throws Exception {
        // The strongest possible check of port fidelity: the asset was built by
        // running talisman's phonex.js over every word, so a correct port must
        // re-derive each word's bucket key.
        Map<String, List<String>> phonexMap;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(TestAssets.asset("phonex_encoded_words.json")), StandardCharsets.UTF_8)) {
            phonexMap = new Gson().fromJson(reader, new TypeToken<Map<String, List<String>>>() {
            }.getType());
        }
        int checked = 0;
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, List<String>> bucket : phonexMap.entrySet()) {
            for (String word : bucket.getValue()) {
                String encoded = Phonex.encode(word);
                if (!bucket.getKey().equals(encoded) && mismatches.size() < 5) {
                    mismatches.add(word + ": expected " + bucket.getKey() + " got " + encoded);
                }
                checked++;
            }
        }
        assertEquals("mismatches: " + mismatches, 0, mismatches.size());
        assertEquals(71251, checked);
    }
}
