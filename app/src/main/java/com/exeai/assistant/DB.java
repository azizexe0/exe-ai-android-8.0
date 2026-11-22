package com.exeai.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local SQLite store. File name and v2 tables are kept from EXE AI 6.0 so existing
 * memory, QA and learned rows survive the upgrade. Version 3 adds reminders and chat history.
 */
public class DB extends SQLiteOpenHelper {
    private static final String NAME = "exe_ai_memory.db";
    private static final int VERSION = 5;

    public static final class Reminder {
        public final long id;
        public final String text;
        public final long dueMillis;
        public final boolean repeatDaily;

        Reminder(long id, String text, long dueMillis, boolean repeatDaily) {
            this.id = id;
            this.text = text;
            this.dueMillis = dueMillis;
            this.repeatDaily = repeatDaily;
        }
    }

    public DB(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS memory(id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT NOT NULL, tag TEXT, created INTEGER)");
        d.execSQL("CREATE TABLE IF NOT EXISTS qa(question_key TEXT PRIMARY KEY, question TEXT NOT NULL, answer TEXT NOT NULL, category TEXT)");
        d.execSQL("CREATE TABLE IF NOT EXISTS learned(question_key TEXT PRIMARY KEY, question TEXT NOT NULL, answer TEXT NOT NULL, source TEXT, created INTEGER)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_qa_category ON qa(category)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_created ON memory(created)");
        createV3(d);
        createV5(d);
        seed(d);
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // 6.0 upgrade path: keep rows, add indexed key columns, remove duplicate keys.
            d.execSQL("CREATE TABLE IF NOT EXISTS learned(question_key TEXT PRIMARY KEY, question TEXT NOT NULL, answer TEXT NOT NULL, source TEXT, created INTEGER)");
            boolean hasKey = false;
            boolean hasCategory = false;
            try (Cursor c = d.rawQuery("PRAGMA table_info(qa)", null)) {
                while (c.moveToNext()) {
                    String column = c.getString(1);
                    if ("question_key".equals(column)) hasKey = true;
                    if ("category".equals(column)) hasCategory = true;
                }
            }
            if (!hasKey) d.execSQL("ALTER TABLE qa ADD COLUMN question_key TEXT");
            if (!hasCategory) d.execSQL("ALTER TABLE qa ADD COLUMN category TEXT");
            d.execSQL("UPDATE qa SET question_key=lower(trim(question)) WHERE question_key IS NULL OR question_key=''");
            d.execSQL("DELETE FROM qa WHERE rowid NOT IN (SELECT MIN(rowid) FROM qa GROUP BY question_key)");
            d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_qa_question_key ON qa(question_key)");
        }
        if (oldVersion < 3) {
            createV3(d);
        }
        if (oldVersion < 4) {
            // Version 4: chat sessions. Older history rows get session 0 (shown as the first chat).
            d.execSQL("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created INTEGER NOT NULL)");
            boolean hasSession = false;
            try (Cursor c = d.rawQuery("PRAGMA table_info(history)", null)) {
                while (c.moveToNext()) {
                    if ("session_id".equals(c.getString(1))) hasSession = true;
                }
            }
            if (!hasSession) {
                d.execSQL("ALTER TABLE history ADD COLUMN session_id INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (oldVersion < 5) {
            createV5(d);
        }
    }

    /** Version 5: day plans and notes. */
    private void createV5(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS plans(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, due INTEGER NOT NULL, created INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_plans_due ON plans(due)");
    }

    private void createV3(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, due INTEGER NOT NULL, repeat_daily INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE IF NOT EXISTS history(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL, session_id INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created INTEGER NOT NULL)");
    }

    static String key(String text) {
        return text == null ? "" : text.trim().toLowerCase(new Locale("tr", "TR")).replaceAll("\\s+", " ");
    }

    // ---------- memory ----------

    public void saveMemory(String fact, String tag) {
        ContentValues v = new ContentValues();
        v.put("fact", fact);
        v.put("tag", tag);
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insert("memory", null, v);
    }

    public List<String> listMemory(int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT fact FROM memory ORDER BY created DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public void clearMemory() {
        getWritableDatabase().delete("memory", null, null);
    }

    // ---------- learned web answers ----------

    public void saveLearned(String question, String answer, String source) {
        ContentValues v = new ContentValues();
        v.put("question_key", key(question));
        v.put("question", question);
        v.put("answer", answer);
        v.put("source", source);
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("learned", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String findLearnedFuzzy(String question) {
        String exact = scalar("SELECT answer FROM learned WHERE question_key=?", key(question));
        if (exact != null) return exact;
        String target = TextUnderstanding.normalize(question);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT question, answer FROM learned LIMIT 500", null)) {
            while (c.moveToNext()) {
                String candidate = TextUnderstanding.normalize(c.getString(0));
                if (TextUnderstanding.containsIntent(target, candidate)
                        || TextUnderstanding.containsIntent(candidate, target)) return c.getString(1);
            }
        }
        return null;
    }

    // ---------- bilgi paketi (QA) ----------

    public String knowledgeFuzzy(String question) {
        String exact = scalar("SELECT answer FROM qa WHERE question_key=?", key(question));
        if (exact != null) return exact;
        String target = TextUnderstanding.normalize(question);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT question, answer FROM qa LIMIT 2000", null)) {
            while (c.moveToNext()) {
                String candidate = TextUnderstanding.normalize(c.getString(0));
                if (TextUnderstanding.containsIntent(target, candidate)
                        || TextUnderstanding.containsIntent(candidate, target)) return c.getString(1);
            }
        }
        return null;
    }

    public void putQa(SQLiteDatabase d, String question, String answer, String category) {
        ContentValues v = new ContentValues();
        v.put("question_key", key(question));
        v.put("question", question);
        v.put("answer", answer);
        v.put("category", category);
        d.insertWithOnConflict("qa", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public long qaCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM qa", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    // ---------- reminders / alarms ----------

    public long addReminder(String text, long dueMillis, boolean repeatDaily) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("due", dueMillis);
        v.put("repeat_daily", repeatDaily ? 1 : 0);
        v.put("done", 0);
        return getWritableDatabase().insert("reminders", null, v);
    }

    public List<Reminder> activeReminders() {
        List<Reminder> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, text, due, repeat_daily FROM reminders WHERE done=0 ORDER BY due ASC", null)) {
            while (c.moveToNext()) {
                out.add(new Reminder(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) == 1));
            }
        }
        return out;
    }

    public Reminder reminderById(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, text, due, repeat_daily FROM reminders WHERE id=?", new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst()) return null;
            return new Reminder(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) == 1);
        }
    }

    public void markReminderDone(long id) {
        ContentValues v = new ContentValues();
        v.put("done", 1);
        getWritableDatabase().update("reminders", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteReminder(long id) {
        getWritableDatabase().delete("reminders", "id=?", new String[]{String.valueOf(id)});
    }

    public void moveReminder(long id, long newDue) {
        ContentValues v = new ContentValues();
        v.put("due", newDue);
        getWritableDatabase().update("reminders", v, "id=?", new String[]{String.valueOf(id)});
    }

    // ---------- chat history ----------

    public void addHistory(long sessionId, String role, String text) {
        ContentValues v = new ContentValues();
        v.put("session_id", sessionId);
        v.put("role", role);
        v.put("text", text);
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insert("history", null, v);
    }

    /** Latest messages of one chat, oldest first, formatted as "role: text". */
    public List<String> recentHistory(long sessionId, int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT role, text FROM history WHERE session_id=? ORDER BY created DESC LIMIT " + limit,
                new String[]{String.valueOf(sessionId)})) {
            while (c.moveToNext()) out.add(0, c.getString(0) + ": " + c.getString(1));
        }
        return out;
    }

    /** All messages of one chat, oldest first. Each entry is {role, text}. */
    public List<String[]> loadSession(long sessionId) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT role, text FROM history WHERE session_id=? ORDER BY created ASC, id ASC",
                new String[]{String.valueOf(sessionId)})) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
        }
        return out;
    }

    // ---------- chat sessions ----------

    public static final class Session {
        public final long id;
        public final String title;

        Session(long id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    public long createSession(String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("sessions", null, v);
    }

    /** Newest chats first. */
    public List<Session> sessions() {
        List<Session> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, title FROM sessions ORDER BY created DESC, id DESC", null)) {
            while (c.moveToNext()) out.add(new Session(c.getLong(0), c.getString(1)));
        }
        return out;
    }

    /** Gives an untitled chat a name taken from its first message. */
    public void nameSessionIfNew(long sessionId, String firstMessage) {
        String name = firstMessage.trim();
        if (name.length() > 30) name = name.substring(0, 30) + "…";
        ContentValues v = new ContentValues();
        v.put("title", name);
        getWritableDatabase().update("sessions", v, "id=? AND title=?",
                new String[]{String.valueOf(sessionId), "Yeni sohbet"});
    }

    // ---------- plans and notes ----------

    public static final class Plan {
        public final long id;
        public final String text;
        public final long dueMillis;

        Plan(long id, String text, long dueMillis) {
            this.id = id;
            this.text = text;
            this.dueMillis = dueMillis;
        }
    }

    public long addPlan(String text, long dueMillis) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("due", dueMillis);
        v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("plans", null, v);
    }

    /** Plans with a due time in [from, to), earliest first. */
    public List<Plan> plansBetween(long from, long to) {
        List<Plan> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, text, due FROM plans WHERE due>=? AND due<? ORDER BY due ASC",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            while (c.moveToNext()) out.add(new Plan(c.getLong(0), c.getString(1), c.getLong(2)));
        }
        return out;
    }

    /** Upcoming plans from a time on, earliest first. */
    public List<Plan> plansFrom(long from, int limit) {
        List<Plan> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, text, due FROM plans WHERE due>=? ORDER BY due ASC LIMIT " + limit,
                new String[]{String.valueOf(from)})) {
            while (c.moveToNext()) out.add(new Plan(c.getLong(0), c.getString(1), c.getLong(2)));
        }
        return out;
    }

    public long addNote(String text) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("notes", null, v);
    }

    /** Latest notes, newest first. */
    public List<String> recentNotes(int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT text FROM notes ORDER BY created DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    // ---------- chat management ----------

    public void renameSession(long sessionId, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("sessions", v, "id=?", new String[]{String.valueOf(sessionId)});
    }

    /** Deletes a chat and all of its messages. */
    public void deleteSession(long sessionId) {
        String id = String.valueOf(sessionId);
        getWritableDatabase().delete("history", "session_id=?", new String[]{id});
        getWritableDatabase().delete("sessions", "id=?", new String[]{id});
    }

    // ---------- helpers ----------

    private String scalar(String sql, String arg) {
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{arg})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private void seed(SQLiteDatabase d) {
        putQa(d, "ram nedir", "RAM, cihazda çalışan uygulamaların kullandığı geçici bellektir.", "elektronik");
        putQa(d, "cpu nedir", "CPU, komutları işleyen ana işlemci birimidir.", "elektronik");
        putQa(d, "gpu nedir", "GPU, grafik işlemlerini hızlandıran işlemci birimidir.", "elektronik");
        putQa(d, "html nedir", "HTML, web sayfalarının yapısını tanımlayan işaretleme dilidir.", "kodlama");
        putQa(d, "java nedir", "Java, Android uygulamaları da geliştirilebilen bir programlama dilidir.", "kodlama");
        putQa(d, "minecraft bedrock nedir", "Minecraft Bedrock, birçok farklı platformda bulunan Minecraft sürümüdür.", "minecraft");
        putQa(d, "redstone nedir", "Redstone, Minecraft'ta devreler ve otomasyon mekanizmaları kurmaya yarar.", "minecraft");
        putQa(d, "internet nedir", "İnternet, dünya çapındaki bilgisayar ağlarını birbirine bağlayan küresel ağdır.", "genel");
        putQa(d, "yapay zeka nedir", "Yapay zekâ, bilgisayarların öğrenme ve dil işleme gibi görevleri yapmasını sağlayan yöntemler alanıdır.", "bilim");
    }
}
