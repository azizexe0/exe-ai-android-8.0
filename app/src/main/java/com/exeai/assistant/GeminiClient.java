package com.exeai.assistant;

import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Gemini REST client with automatic model selection.
 * The app asks Google which models this API key can use, tries them in order, and moves on to the
 * next model when one is missing (404) or keeps failing with a temporary error (503, 429, timeout).
 * The API key is sent in a header, never in the URL.
 */
public final class GeminiClient {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** Total time allowed for a short question's answer, including model retries. */
    public static final long TOTAL_BUDGET_MS = 20000L;
    /** Shown when no answer could be produced in time. Does not mention the service by name. */
    private static final String BUSY_MESSAGE = "Şu an cevap veremiyorum efendim. Biraz sonra tekrar dener misin?";

    /** Total time allowed for longer questions. An answer is shown as soon as it arrives. */
    public static final long LONG_BUDGET_MS = 120000L;
    private static final int ATTEMPTS_PER_MODEL = 2;
    private static final long MODEL_LIST_CACHE_MS = 6L * 60L * 60L * 1000L;

    /** Used only when the model list itself cannot be fetched. */
    private static final List<String> FALLBACK_MODELS = Arrays.asList("gemini-3.8-flash", "gemini-3.5-flash");

    // Cache of usable models, newest and fastest first. Guarded by the class lock.
    private static List<String> cachedModels = null;
    private static long cachedAt = 0L;

    public static final String SYSTEM_PROMPT =
            "Sen EXE AI'sin. Türkçe konuşursun ve kullanıcıya 'efendim' diye hitap edersin. "
          + "Kendini gerçek bir insan olarak tanıtmazsın. Sana kimin kod yazdığı sorulursa "
          + "'Beni Aziz EXE kodladı.' diye cevap verirsin. "
          + "Kullanıcının yazım hatalarını ona söylemeden bağlamdan anlamaya çalışırsın. "
          + "Mesajı gerçekten anlayamazsan tahmin uydurmaz, 'Anlamadım efendim.' dersin. "
          + "Cevapların kısa, net ve mobil ekrana uygun olsun.";

    private GeminiClient() {}

    public static final class GeminiException extends Exception {
        /** HTTP status, or 0 for network and parsing errors. */
        public final int status;
        /** True for temporary failures (503, 429, 5xx, timeouts) that are worth retrying. */
        public final boolean retryable;

        public GeminiException(String message) { this(message, 0, false); }

        public GeminiException(String message, int status, boolean retryable) {
            super(message);
            this.status = status;
            this.retryable = retryable;
        }
    }

    /** A successful answer together with the model that produced it. */
    public static final class Answer {
        public final String model;
        public final String text;

        Answer(String model, String text) {
            this.model = model;
            this.text = text;
        }
    }

    /** Simple questions start with the fast tier; long or analytical ones start with the pro tier. */
    public static boolean isComplex(String text, boolean hasImage) {
        if (hasImage) return false;
        String t = text == null ? "" : text.toLowerCase(new Locale("tr", "TR"));
        return t.length() > 140
                || t.contains("neden") || t.contains("karşılaştır") || t.contains("karsilastir")
                || t.contains("kod") || t.contains("adım adım") || t.contains("analiz")
                || t.contains("planla") || t.contains("hesapla");
    }

    /** Checks the key by asking the best available model a short question. Returns "model: reply". */
    public static String testConnection(String apiKey) throws GeminiException {
        Answer a = askDetailed(apiKey, false, "Kısaca 'bağlantı tamam' yaz.", "Merhaba", null, TOTAL_BUDGET_MS);
        return a.model + ": " + a.text;
    }

    public static String ask(String apiKey, boolean complex, String systemPrompt, String userText, Bitmap image,
                             long budgetMs) throws GeminiException {
        return askDetailed(apiKey, complex, systemPrompt, userText, image, budgetMs).text;
    }

    /**
     * Tries the usable models in order. A 404 moves to the next model at once. Temporary errors are
     * retried a couple of times on the same model before moving on. Key or request errors stop at once.
     */
    public static Answer askDetailed(String apiKey, boolean complex, String systemPrompt, String userText,
                                     Bitmap image, long budgetMs) throws GeminiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new GeminiException("Gemini API anahtarı tanımlı değil. Gizli ayarları açıp anahtarı girebilirsin.");
        }
        List<String> order = new ArrayList<>(modelsForKey(apiKey));
        if (complex) {
            // Pro models first, then the rest: escalates to the stronger model for hard questions.
            List<String> pro = new ArrayList<>();
            List<String> others = new ArrayList<>();
            for (String m : order) {
                if (tier(m) == 1) pro.add(m); else others.add(m);
            }
            order = new ArrayList<>(pro);
            order.addAll(others);
        }

        long deadline = System.currentTimeMillis() + budgetMs;
        GeminiException last = null;
        outer:
        for (String model : order) {
            for (int attempt = 1; attempt <= ATTEMPTS_PER_MODEL; attempt++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining < 3000L) break outer;              // not enough time left for another request
                try {
                    return new Answer(model,
                            requestText(apiKey, model, systemPrompt, userText, image, (int) remaining));
                } catch (GeminiException e) {
                    last = e;
                    if (e.status == 404) break;                  // model not available for this key
                    if (!e.retryable) throw e;                   // key, request or quota-blocked: stop here
                    long left = deadline - System.currentTimeMillis();
                    if (attempt < ATTEMPTS_PER_MODEL && left > 4000L) {
                        pause(Math.min(2000L * attempt, left - 3000L));
                    }
                }
            }
        }
        if (System.currentTimeMillis() >= deadline || last == null) {
            throw new GeminiException(BUSY_MESSAGE, 0, false);
        }
        throw new GeminiException(BUSY_MESSAGE, 0, false);
    }

    // ---------- model list ----------

    /** Returns usable models ordered fast-first, newest-first. Falls back to a fixed list if listing fails. */
    private static synchronized List<String> modelsForKey(String apiKey) throws GeminiException {
        long now = System.currentTimeMillis();
        if (cachedModels != null && !cachedModels.isEmpty() && now - cachedAt < MODEL_LIST_CACHE_MS) {
            return cachedModels;
        }
        try {
            List<String> models = fetchModelList(apiKey);
            cachedModels = models;
            cachedAt = now;
            return models;
        } catch (GeminiException e) {
            // A bad key must be reported; any other listing failure falls back to known names.
            if (e.status == 401 || e.status == 403) throw e;
            return FALLBACK_MODELS;
        }
    }

    private static List<String> fetchModelList(String apiKey) throws GeminiException {
        Response r;
        try {
            r = send("GET", BASE + "models?pageSize=200", apiKey, null, 8000);
        } catch (java.net.UnknownHostException e) {
            throw new GeminiException("İnternet bağlantısı yok ya da sunucuya ulaşılamadı.");
        } catch (java.net.SocketTimeoutException e) {
            throw new GeminiException("Model listesi zaman aşımına uğradı.", 0, true);
        } catch (Exception e) {
            throw new GeminiException("Model listesi alınamadı: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (r.code != 200) throw new GeminiException(httpMessage(r.code, r.raw), r.code, isRetryable(r.code));
        try {
            JSONArray arr = new JSONObject(r.raw).optJSONArray("models");
            List<String> out = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    String name = m.optString("name", "").replace("models/", "");
                    JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                    if (methods == null || !methods.toString().contains("generateContent")) continue;
                    if (usable(name)) out.add(name);
                }
            }
            if (out.isEmpty()) throw new GeminiException("Bu anahtarla kullanılabilir Gemini modeli bulunamadı.");
            Collections.sort(out, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int t = Integer.compare(tier(a), tier(b));
                    if (t != 0) return t;
                    int v = Double.compare(version(b), version(a));
                    return v != 0 ? v : b.compareTo(a);
                }
            });
            return out;
        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiException("Model listesi okunamadı.");
        }
    }

    /** Keeps chat-capable text models; drops embedding, speech, image, live and similar models. */
    private static boolean usable(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.startsWith("gemini-")) return false;
        String[] excluded = {"embed", "tts", "image", "live", "aqa", "audio", "robotics", "computer", "native"};
        for (String e : excluded) {
            if (n.contains(e)) return false;
        }
        return true;
    }

    /** 0 = flash (fast), 1 = pro (strong), 2 = anything else. */
    private static int tier(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("flash")) return 0;
        if (n.contains("pro")) return 1;
        return 2;
    }

    private static double version(String name) {
        Matcher m = Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)").matcher(name);
        if (!m.find()) return 0d;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    // ---------- requests ----------

    private static String requestText(String apiKey, String model, String systemPrompt, String userText, Bitmap image,
                                      int readTimeoutMs) throws GeminiException {
        try {
            JSONObject body = new JSONObject();
            body.put("system_instruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))));

            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", userText));
            if (image != null) {
                parts.put(new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", encodeImage(image))));
            }
            body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));

            JSONObject generation = new JSONObject();
            generation.put("temperature", 0.6);
            generation.put("maxOutputTokens", 1024);
            body.put("generationConfig", generation);

            Response r = send("POST", BASE + "models/" + model + ":generateContent", apiKey,
                    body.toString().getBytes(StandardCharsets.UTF_8), Math.max(3000, readTimeoutMs));
            if (r.code != 200) throw new GeminiException(httpMessage(r.code, r.raw), r.code, isRetryable(r.code));
            return extractText(r.raw);
        } catch (GeminiException e) {
            throw e;
        } catch (java.net.UnknownHostException e) {
            throw new GeminiException("İnternet bağlantısı yok ya da sunucuya ulaşılamadı.");
        } catch (java.net.SocketTimeoutException e) {
            throw new GeminiException(BUSY_MESSAGE, 0, true);
        } catch (Exception e) {
            throw new GeminiException("Gemini bağlantı hatası: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static final class Response {
        final int code;
        final String raw;

        Response(int code, String raw) {
            this.code = code;
            this.raw = raw;
        }
    }

    private static Response send(String method, String url, String apiKey, byte[] payload, int readTimeoutMs)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("x-goog-api-key", apiKey.trim());
            if (payload != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }
            int code = conn.getResponseCode();
            String raw = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return new Response(code, raw);
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isRetryable(int code) {
        return code == 429 || code >= 500;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String encodeImage(Bitmap bitmap) {
        // Downscale large photos so the request stays small.
        Bitmap scaled = bitmap;
        int max = 1024;
        if (bitmap.getWidth() > max || bitmap.getHeight() > max) {
            float ratio = Math.min(max / (float) bitmap.getWidth(), max / (float) bitmap.getHeight());
            scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.round(bitmap.getWidth() * ratio), Math.round(bitmap.getHeight() * ratio), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private static String extractText(String raw) throws GeminiException {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new GeminiException("Gemini boş cevap döndürdü. Sorunu biraz farklı yazabilir misin?");
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            if (content == null) throw new GeminiException("Gemini içerik döndürmedi (güvenlik filtresi olabilir).");
            JSONArray parts = content.optJSONArray("parts");
            StringBuilder out = new StringBuilder();
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    String t = parts.getJSONObject(i).optString("text", "");
                    if (!t.isEmpty()) out.append(t);
                }
            }
            String text = out.toString().trim();
            if (text.isEmpty()) throw new GeminiException("Gemini metin döndürmedi. Anlamadım efendim.");
            return text;
        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiException("Gemini cevabı okunamadı.");
        }
    }

    private static String httpMessage(int code, String raw) {
        String apiMessage = "";
        try {
            apiMessage = new JSONObject(raw).getJSONObject("error").optString("message", "");
        } catch (Exception ignored) { }
        switch (code) {
            case 400: return "Gemini isteği reddetti (400). " + apiMessage;
            case 401:
            case 403: return "API anahtarı geçersiz veya yetkin yok (" + code + "). " + apiMessage;
            case 404: return "Model bulunamadı (404). " + apiMessage;
            case 429: return "Gemini kota sınırına ulaştı (429). Biraz bekleyip tekrar dene.";
            default:
                if (code >= 500) return "Gemini sunucu hatası (" + code + "). Yoğunluk geçici olabilir.";
                return "Gemini hatası (" + code + "). " + apiMessage;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
