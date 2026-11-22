package com.exeai.assistant;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public-web research without an API key. Reads accessible search results and pages. */
public final class WebResearcher {
    private static final String UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 EXEAI/8.0";
    private WebResearcher() {}

    public static final class Result {
        public final String answer;
        public final String sources;
        Result(String answer, String sources) { this.answer = answer; this.sources = sources; }
    }

    public static Result research(String query) throws Exception {
        Map<String, String> found = new LinkedHashMap<>();
        searchDuckDuckGo(query, found);
        searchBingRss(query, found);
        if (found.isEmpty()) return null;

        StringBuilder answer = new StringBuilder();
        StringBuilder sources = new StringBuilder();
        int used = 0;
        for (Map.Entry<String, String> item : found.entrySet()) {
            if (used >= 4) break;
            String url = item.getKey();
            String title = item.getValue();
            try {
                Document page = Jsoup.connect(url).userAgent(UA).timeout(10000)
                        .maxBodySize(900_000).followRedirects(true).get();
                page.select("script,style,nav,footer,header,form,aside,noscript,svg").remove();
                Element content = page.selectFirst("article, main, [role=main]");
                String text = (content != null ? content : page.body()).text();
                text = clean(text);
                if (text.length() < 180) continue;
                if (answer.length() > 0) answer.append("\n\n");
                answer.append("• ").append(title).append("\n").append(excerpt(text, 1050));
                if (sources.length() > 0) sources.append("\n");
                sources.append(url);
                used++;
            } catch (Exception ignored) {
                // Skip inaccessible, script-only, blocked or unsupported pages.
            }
        }
        if (used == 0) {
            // Search snippets are still useful when sites block automated page access.
            for (Map.Entry<String, String> item : found.entrySet()) {
                if (used >= 3) break;
                if (sources.length() > 0) sources.append("\n");
                sources.append(item.getKey());
                answer.append("• ").append(item.getValue()).append("\n");
                used++;
            }
        }
        if (answer.length() == 0) return null;
        return new Result(answer.toString().trim(), sources.toString());
    }

    private static void searchDuckDuckGo(String query, Map<String, String> found) {
        try {
            String url = "https://html.duckduckgo.com/html/?q=" + enc(query);
            Document doc = Jsoup.connect(url).userAgent(UA).timeout(12000).maxBodySize(700_000).get();
            Elements links = doc.select("a.result__a");
            for (Element link : links) {
                String href = link.absUrl("href");
                String title = clean(link.text());
                if (valid(href) && !title.isEmpty()) found.putIfAbsent(href, title);
                if (found.size() >= 8) break;
            }
        } catch (Exception ignored) { }
    }

    private static void searchBingRss(String query, Map<String, String> found) {
        try {
            String url = "https://www.bing.com/search?format=rss&q=" + enc(query);
            Document doc = Jsoup.connect(url).userAgent(UA).timeout(12000).maxBodySize(700_000)
                    .parser(Parser.xmlParser()).get();
            for (Element item : doc.select("item")) {
                String href = item.select("link").text().trim();
                String title = clean(item.select("title").text());
                if (valid(href) && !title.isEmpty()) found.putIfAbsent(href, title);
                if (found.size() >= 10) break;
            }
        } catch (Exception ignored) { }
    }

    private static boolean valid(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"))
                && !url.contains("duckduckgo.com/y.js") && !url.contains("bing.com/ck/a");
    }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); }
    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }
    private static String excerpt(String text, int max) {
        if (text.length() <= max) return text;
        int end = text.lastIndexOf(' ', max);
        return text.substring(0, end > 300 ? end : max) + "…";
    }
}
