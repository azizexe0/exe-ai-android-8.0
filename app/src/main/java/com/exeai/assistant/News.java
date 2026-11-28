package com.exeai.assistant;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Reads today's Turkish headlines from the Google News RSS feed. No API key needed. */
public final class News {
    private static final String FEED = "https://news.google.com/rss?hl=tr&gl=TR&ceid=TR:tr";

    public static final class Item {
        public final String title;
        public final String link;

        Item(String title, String link) {
            this.title = title;
            this.link = link;
        }
    }

    private News() {}

    /** Returns up to {@code max} headlines in feed order. Throws when the feed cannot be read. */
    public static List<Item> fetch(int max) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(FEED).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) EXE-AI/9.0");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("haber kaynağı yanıt vermedi (" + code + ")");
            List<Item> out = new ArrayList<>();
            try (InputStream in = conn.getInputStream()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                parser.setInput(in, "UTF-8");
                String title = null;
                String link = null;
                String tag = null;
                boolean inItem = false;
                int event = parser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT && out.size() < max) {
                    if (event == XmlPullParser.START_TAG) {
                        tag = parser.getName();
                        if ("item".equals(tag)) {
                            inItem = true;
                            title = null;
                            link = null;
                        }
                    } else if (event == XmlPullParser.TEXT && inItem) {
                        if ("title".equals(tag)) title = (title == null ? "" : title) + parser.getText();
                        else if ("link".equals(tag)) link = (link == null ? "" : link) + parser.getText();
                    } else if (event == XmlPullParser.END_TAG) {
                        String name = parser.getName();
                        tag = null;
                        if ("item".equals(name)) {
                            if (title != null && !title.trim().isEmpty()) {
                                out.add(new Item(title.trim(), link == null ? "" : link.trim()));
                            }
                            inItem = false;
                        }
                    }
                    event = parser.next();
                }
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }
}
