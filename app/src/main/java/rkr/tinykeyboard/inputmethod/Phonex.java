package rkr.tinykeyboard.inputmethod;

/**
 * Java port of the "Phonex" phonetic algorithm used by hallelujahIM, from
 * Talisman (https://github.com/Yomguithereal/talisman, MIT license), itself an
 * implementation of Lait & Randell, "An Assessment of Name Matching
 * Algorithms". Must stay in sync with hallelujahIM's src/phonex.js, which
 * builds the phonex_encoded_words.json asset this app ships.
 */
public final class Phonex {
    private Phonex() {
    }

    private static final String[] INITIAL_SETS = {"AEIOUY", "BP", "VF", "KQC", "JG", "ZS"};
    private static final String INITIAL_TARGETS = "ABFCGS";
    private static final String B_SET = "BPFV";
    private static final String C_SET = "CSKGJQXZ";
    private static final String VOWELS_SET = "AEIOUY";

    public static String encode(String word) {
        if (word == null || word.isEmpty()) {
            return "";
        }
        String name = word.toUpperCase().replaceAll("[^A-Z]", "");
        if (name.isEmpty()) {
            return "";
        }

        // Removing trailing S
        name = name.replaceAll("S+$", "");
        if (name.isEmpty()) {
            return "";
        }

        // Substitution of some initials
        String firstTwoLetter = name.length() >= 2 ? name.substring(0, 2) : name;
        if (firstTwoLetter.equals("KN")) {
            name = "N" + name.substring(2);
        } else if (firstTwoLetter.equals("PH")) {
            name = "F" + name.substring(2);
        } else if (firstTwoLetter.equals("WR")) {
            name = "R" + name.substring(2);
        }

        // Ignoring first H if present
        if (!name.isEmpty() && name.charAt(0) == 'H') {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            return "";
        }

        // Encoding first character
        for (int i = 0; i < INITIAL_SETS.length; i++) {
            if (INITIAL_SETS[i].indexOf(name.charAt(0)) >= 0) {
                name = INITIAL_TARGETS.charAt(i) + name.substring(1);
                break;
            }
        }

        char first = name.charAt(0);
        StringBuilder code = new StringBuilder();
        code.append(first);
        char last = first;
        int length = name.length();

        for (int i = 1; i < length; i++) {
            char letter = name.charAt(i);
            // JS reads name[i + 1] (undefined at the end); '\0' plays that role.
            char nextLetter = i + 1 < length ? name.charAt(i + 1) : '\0';

            char encoding = '0';

            if (B_SET.indexOf(letter) >= 0) {
                encoding = '1';
            } else if (C_SET.indexOf(letter) >= 0) {
                encoding = '2';
            } else if (letter == 'D' || letter == 'T') {
                if (nextLetter != 'C') {
                    encoding = '3';
                }
            } else if (letter == 'L') {
                if (VOWELS_SET.indexOf(nextLetter) >= 0 || i + 1 == length) {
                    encoding = '4';
                }
            } else if (letter == 'M' || letter == 'N') {
                if (nextLetter == 'D' || nextLetter == 'G') {
                    name = name.substring(0, i + 1) + letter + name.substring(i + 2);
                }
                encoding = '5';
            } else if (letter == 'R') {
                if (VOWELS_SET.indexOf(nextLetter) >= 0 || i + 1 == length) {
                    encoding = '6';
                }
            }

            if (encoding != last && encoding != '0') {
                code.append(encoding);
            }
            last = code.charAt(code.length() - 1);
        }

        return code.toString();
    }
}
