package com.exeai.assistant;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fixes common Turkish chat shorthand and close misspellings before local rules run.
 * It works offline and never shows the corrections to the user; the original text is kept for Gemini.
 */
public final class ChatNormalizer {
    private static final Map<String, String> SHORTHAND = new HashMap<>();
    private static final List<String> VOCABULARY = Arrays.asList(
            "merhaba", "selam", "nasilsin", "haber", "teşekkür", "tesekkur", "günaydın", "gunaydin",
            "alarm", "hatırlat", "hatirlat", "hatırlatıcı", "hatirlatici", "listele", "uygulama",
            "yardım", "yardim", "kamera", "galeri", "wifi", "bluetooth", "minecraft", "youtube",
            "hafıza", "hafiza", "saat", "yarın", "yarin", "dakika", "sonra", "kurar", "kur", "sil",
            "aziz", "exe", "efendim", "espiri", "komik");

    static {
        SHORTHAND.put("mrb", "merhaba");
        SHORTHAND.put("mrba", "merhaba");
        SHORTHAND.put("mrhb", "merhaba");
        SHORTHAND.put("slm", "selam");
        SHORTHAND.put("sa", "selam");
        SHORTHAND.put("nbr", "ne haber");
        SHORTHAND.put("nbrr", "ne haber");
        SHORTHAND.put("nsl", "nasilsin");
        SHORTHAND.put("nslsn", "nasilsin");
        SHORTHAND.put("nasilsn", "nasilsin");
        SHORTHAND.put("tsk", "tesekkur");
        SHORTHAND.put("tşk", "tesekkur");
        SHORTHAND.put("tskler", "tesekkurler");
        SHORTHAND.put("sagol", "sagol");
        SHORTHAND.put("gnydn", "gunaydin");
        SHORTHAND.put("gunydn", "gunaydin");
        SHORTHAND.put("kmr", "kimdir");
        SHORTHAND.put("azz", "aziz");
        SHORTHAND.put("ex", "exe");
        SHORTHAND.put("yt", "youtube");
        SHORTHAND.put("mc", "minecraft");
        SHORTHAND.put("hatrlt", "hatirlat");
        SHORTHAND.put("alrm", "alarm");
    }

    private ChatNormalizer() {}

    /** Returns the text with shorthand replaced and close misspellings corrected word by word. */
    public static String fix(String input) {
        if (input == null) return "";
        String lower = input.trim().toLowerCase(new Locale("tr", "TR"));
        StringBuilder out = new StringBuilder();
        for (String token : lower.split("\\s+")) {
            String bare = token.replaceAll("[^\\p{L}\\p{N}]", "");
            if (bare.isEmpty()) continue;
            String fixed = SHORTHAND.containsKey(bare) ? SHORTHAND.get(bare) : bare;
            if (fixed.equals(bare) && bare.length() >= 4) {
                fixed = TextUnderstanding.bestCorrection(bare, VOCABULARY);
            }
            if (out.length() > 0) out.append(' ');
            out.append(fixed);
        }
        return out.toString();
    }
}
