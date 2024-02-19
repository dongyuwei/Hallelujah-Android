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
    private static PatriciaTrie<Double> trie = new PatriciaTrie<>();
    private static Map<String, List<WordInfo>> pinyinDict = new HashMap<>();

    public static void buildPinyinDict(String content) {
        String[] linesArray = content.split("\\R"); // "\\R" is a regex that matches any line terminator
        List<String> lines = Stream.of(linesArray)
                .filter(line -> line.contains(" 0 ")) // ' 0 ' means simplified Chinese characters
                .collect(Collectors.toList());

        lines.forEach(line -> {
            String[] arr = line.split(" 0 ");
            // 董 2494.97706011 0 dong
            // 西红柿 760.851466162 0 xi hong shi

            if (arr.length == 2) {
                String abbr = Arrays.stream(arr[1].split(" "))
                        .map(item -> item.substring(0, 1))
                        .collect(Collectors.joining());

                String pinyin = arr[1].replace(" ", "");
                String[] wordFrequency = arr[0].split(" ");
                String word = wordFrequency[0];
                double frequency = Double.parseDouble(wordFrequency[1]);
                WordInfo wordInfo = new WordInfo(word, frequency);
                trie.put(pinyin, frequency);
                pinyinDict.computeIfAbsent(pinyin, k -> new ArrayList<>()).add(wordInfo);
                if (abbr.length() >= 1) {
                    pinyinDict.computeIfAbsent(abbr, k -> new ArrayList<>()).add(wordInfo);
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
                // pinyin prefix match
                list = getCandidatesFromTrie(input);
            }

            // Sort candidates by word frequency
            candidates = list.stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted((a, b) -> Double.compare(b.getFrequency(), a.getFrequency()))
                    .map(WordInfo::getWord)
                    .distinct()
                    .collect(Collectors.toList());
        }

        return candidates;
    }

    private static List<WordInfo> getCandidatesFromTrie(String prefix) {
        List<WordInfo> candidates = new ArrayList<>();
        Map<String, Double> prefixMap = trie.prefixMap(prefix);
        if (!prefixMap.isEmpty()) {
            List<Map.Entry<String, Double>> matchingWords = new ArrayList<>(prefixMap.entrySet());
            for (Map.Entry<String, Double> entry : matchingWords) {
                List<WordInfo> words = pinyinDict.get(entry.getKey());
                if (words != null) {
                    candidates.addAll(words);
                }
            }
        }
        return candidates;
    }

    static class WordInfo {
        public String getWord() {
            return word;
        }

        public double getFrequency() {
            return frequency;
        }

        String word;
        double frequency;

        WordInfo(String word, double frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }
}
