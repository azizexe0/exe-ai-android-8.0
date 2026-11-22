package com.exeai.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Offline, typo-tolerant Turkish text normalization and fuzzy matching. */
public final class TextUnderstanding {
    private TextUnderstanding() {}

    public static String normalize(String value) {
        if (value == null) return "";
        String s = value.toLowerCase(new Locale("tr", "TR"))
                .replace('ı', 'i').replace('İ', 'i');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
        return s;
    }

    public static int distance(String a, String b) {
        a = a == null ? "" : a;
        b = b == null ? "" : b;
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    public static boolean wordMatches(String actual, String expected) {
        String a = normalize(actual), b = normalize(expected);
        if (a.equals(b)) return true;
        if (a.length() < 3 || b.length() < 3) return false;
        int max = Math.max(a.length(), b.length());
        int allowed = max >= 9 ? 2 : (max >= 5 ? 1 : 0);
        return distance(a, b) <= allowed;
    }

    public static boolean containsIntent(String input, String... examples) {
        String normalizedInput = normalize(input);
        if (normalizedInput.isEmpty()) return false;
        List<String> inputWords = words(normalizedInput);
        for (String example : examples) {
            String target = normalize(example);
            if (target.isEmpty()) continue;
            if (normalizedInput.equals(target) || normalizedInput.contains(target)) return true;
            List<String> targetWords = words(target);
            int matched = 0;
            for (String tw : targetWords) {
                boolean found = false;
                for (String iw : inputWords) {
                    if (wordMatches(iw, tw)) { found = true; break; }
                }
                if (found) matched++;
            }
            if (!targetWords.isEmpty() && matched >= Math.max(1, targetWords.size() - 1)
                    && matched / (double) targetWords.size() >= 0.66) return true;
        }
        return false;
    }

    public static String bestCorrection(String word, List<String> vocabulary) {
        String w = normalize(word);
        if (w.length() < 4) return w;
        String best = w;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : vocabulary) {
            String c = normalize(candidate);
            if (c.length() < 3 || Math.abs(c.length() - w.length()) > 2) continue;
            int dist = distance(w, c);
            int allowed = Math.max(1, Math.min(2, w.length() / 5));
            if (dist <= allowed && dist < bestDistance) {
                best = c;
                bestDistance = dist;
            }
        }
        return best;
    }

    public static String normalizeAndCorrect(String input, List<String> vocabulary) {
        List<String> result = new ArrayList<>();
        for (String word : words(normalize(input))) result.add(bestCorrection(word, vocabulary));
        return String.join(" ", result);
    }


    /** Small typo/slang normalization pass used before public-web searches. */
    public static String prepareSearchQuery(String input) {
        String normalized = normalize(input);
        StringBuilder out = new StringBuilder();
        for (String word : words(normalized)) {
            String fixed = word;
            switch (word) {
                case "azz": fixed = "aziz"; break;
                case "ex": fixed = "exe"; break;
                case "kmr": case "kim": fixed = "kimdir"; break;
                case "nsl": fixed = "nasilsin"; break;
                case "slm": fixed = "selam"; break;
                case "mcrft": fixed = "minecraft"; break;
                case "whatsap": case "watsap": fixed = "whatsapp"; break;
                case "yt": fixed = "youtube"; break;
                case "ac": fixed = "ac"; break;
                default: break;
            }
            if (out.length() > 0) out.append(' ');
            out.append(fixed);
        }
        return out.toString();
    }

    private static List<String> words(String text) {
        if (text == null || text.trim().isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(text.split("\\s+")));
    }
}
