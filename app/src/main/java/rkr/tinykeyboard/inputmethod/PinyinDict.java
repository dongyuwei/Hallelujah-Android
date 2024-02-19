package rkr.tinykeyboard.inputmethod;

import org.apache.commons.collections4.trie.PatriciaTrie;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PinyinDict {
    private static PatriciaTrie<Float> trie = new PatriciaTrie<>();
    private static Map<String, List<WordInfo>> pinyinDict = new HashMap<>();

    public static void buildPinyinDict(String content) {
        String[] linesArray = content.split("\\R"); // "\\R" is a regex that matches any line terminator
        List<String> lines = Stream.of(linesArray)
                .filter(line -> line.contains(" 0 ")) // ' 0 ' means simplified Chinese characters
                .collect(Collectors.toList());

        lines.forEach(line -> {
            String[] arr = line.split(" 0 ");

            if (arr.length == 2) {
                String abbr = Arrays.stream(arr[1].split(" "))
                        .map(item -> item.substring(0, 1))
                        .collect(Collectors.joining());

                String pinyin = arr[1].replaceAll("\\s+", "");
                String[] wordFrequency = arr[0].split(" ");
                String word = wordFrequency[0];
                float frequency = Float.parseFloat(wordFrequency[1]);
                WordInfo value = new WordInfo(word, frequency);
                trie.put(word, frequency);
                pinyinDict.computeIfAbsent(pinyin, k -> new ArrayList<>()).add(value);
                if (abbr.length() >= 1) {
                    pinyinDict.computeIfAbsent(abbr, k -> new ArrayList<>()).add(value);
                }
            }
        });
    }

    public static List<String> getCandidates(String input) {
        List<WordInfo> list = new ArrayList<>();
        List<String> candidates = new ArrayList<>();

        if (input != null && !input.isEmpty()) {
            List<WordInfo> value = pinyinDict.get(input);
            if (value != null) {
                // Full pinyin match or abbr match
                list = value;
            } else if (input.length() >= 1) {
                Map<String, Float> prefixMap = trie.prefixMap(input);
                List<Map.Entry<String, Float>> matchingWords = new ArrayList<>(prefixMap.entrySet());
                if (!matchingWords.isEmpty()) {
                    for (Map.Entry<String, Float> entry : matchingWords) {
                        List<WordInfo> words = pinyinDict.get(entry.getKey());
                        if (words != null) {
                            list = words;
                        }
                    }
                }
            }

            // Sort candidates by word frequency
            candidates = list.stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted((a, b) -> Float.compare(b.getFrequency(), a.getFrequency()))
                    .map(WordInfo::getWord)
                    .distinct()
                    .collect(Collectors.toList());
        }

        // Removing duplicates
        return candidates;
    }

    static class WordInfo {
        public String getWord() {
            return word;
        }

        public float getFrequency() {
            return frequency;
        }

        String word;
        float frequency;

        WordInfo(String word, float frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }
}
