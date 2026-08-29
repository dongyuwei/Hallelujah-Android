package rkr.tinykeyboard.inputmethod;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.Before;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpellCheckerTest {

    private SpellChecker spellChecker;

    @Before
    public void loadDictionary() {
        Type type = new TypeToken<Map<String, List<String>>>() {
        }.getType();
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(TestAssets.asset("phonex_encoded_words.json")), StandardCharsets.UTF_8)) {
            Map<String, List<String>> phonexMap = new Gson().fromJson(reader, type);
            spellChecker = new SpellChecker(phonexMap);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void suggestsClosestWords_forATypo() {
        // "extendd" encodes to A235 (the ND rule replaces the D), the same
        // bucket as "extend", which is distance 1 away.
        List<String> suggestions = spellChecker.getSuggestions("extendd");

        assertEquals("extend", suggestions.get(0));
        assertTrue(suggestions.size() <= SpellChecker.MAX_SUGGESTIONS);
    }

    @Test
    public void suggestsWords_withinTheEditDistanceThreshold() {
        List<String> suggestions = spellChecker.getSuggestions("estmate"); // missing i -> estimate

        assertTrue(suggestions.contains("estimate"));
        for (String suggestion : suggestions) {
            assertTrue(EditDistance.distance("estmate", suggestion) <= SpellChecker.MAX_EDIT_DISTANCE);
        }
    }

    @Test
    public void ignoresShortInput() {
        assertTrue(spellChecker.getSuggestions("").isEmpty());
        assertTrue(spellChecker.getSuggestions("abc").isEmpty());
    }

    @Test
    public void returnsEmpty_whenNoPhoneticBucketMatches() {
        assertTrue(spellChecker.getSuggestions("qwrtzpbv").isEmpty());
    }
}
