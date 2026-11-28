package com.exeai.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final int SPEECH = 41;
    private static final int IMAGE_PICK = 42;
    private static final int CAMERA = 43;
    private static final int IMPORT_QA = 44;
    private static final int FILE_PICK = 45;
    private static final int NOTIFY_PERMISSION = 46;
    private static final int APK_PICK = 47;

    private static final int RED = Color.rgb(255, 45, 55);
    private static final int BLACK = Color.rgb(10, 10, 10);
    private static final int PANEL = Color.rgb(28, 28, 28);
    private static final int BUTTON_BG = Color.rgb(30, 30, 30);
    private static final int MAX_FILE_CHARS = 12000;
    private static final String FILE_PROVIDER = "com.exeai.assistant.fileprovider";

    private static final Pattern WAKE_WORD = Pattern.compile("^(hey\\s+)?exe\\b[,.!?]?\\s*", Pattern.CASE_INSENSITIVE);

    private DrawerLayout drawer;
    private LinearLayout chat;
    private LinearLayout sessionList;
    private EditText input;
    private ScrollView scroll;
    private TextView statusLine;
    private DB db;
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private long currentSession = 0;
    private boolean ttsReady = false;
    private boolean voiceEnabled = true;
    private boolean liveVoice = false;
    private String selectedVoiceName = "";
    private float speechRate = 1.0f;
    private float speechPitch = 1.0f;
    private Bitmap pendingBitmap = null;
    private String pendingFileText = null;

    // ---------- lifecycle ----------

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);

        prefs = getSharedPreferences("exe_ai_settings", MODE_PRIVATE);
        voiceEnabled = prefs.getBoolean("voice_enabled", true);
        selectedVoiceName = prefs.getString("voice_name", "");
        speechRate = prefs.getFloat("speech_rate", 1.0f);
        speechPitch = prefs.getFloat("speech_pitch", 1.0f);

        db = new DB(this);
        Reminders.createChannel(this);
        worker.execute(() -> Reminders.armAll(this, db));

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                tts.setLanguage(new Locale("tr", "TR"));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) {
                        if (liveVoice) mainHandler.post(MainActivity.this::listen);
                    }
                    @Override public void onError(String utteranceId) {
                        if (liveVoice) mainHandler.post(MainActivity.this::listen);
                    }
                });
                applySavedVoice();
            }
        });

        buildUI();
        openLatestSession();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFY_PERMISSION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        db.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------- secret silent mode (type 5087) ----------

    private boolean secretMode = false;
    private FrameLayout stageLayout = null;   // black base; the orb sits on it, behind the UI
    private LinearLayout mainLayout = null;   // the normal screen (top bar, chat, bottom bar)
    private ArcReactorView secretReactor = null;

    private static String orbKey(long sessionId) {
        return "orb_" + sessionId;
    }

    /** Turns the orb on or off for the current chat. The setting is saved, so it stays after restarts. */
    private void setOrbForSession(boolean on) {
        prefs.edit().putBoolean(orbKey(currentSession), on).apply();
        applySecretMode(on);
    }

    /**
     * Shows or hides the red arc reactor behind the normal screen. The menus and chat stay as they are;
     * only the black background is replaced by the orb. While it is on, replies are shown as text only, not spoken.
     */
    private void applySecretMode(boolean on) {
        secretMode = on;
        if (secretMode) {
            if (secretReactor == null && stageLayout != null) {
                secretReactor = new ArcReactorView(this);
                stageLayout.addView(secretReactor, 0,
                        new FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER));
            }
            if (secretReactor != null) secretReactor.setVisibility(View.VISIBLE);
            if (mainLayout != null) mainLayout.setBackgroundColor(Color.TRANSPARENT);
            liveVoice = false;
            if (tts != null) tts.stop();
        } else {
            if (secretReactor != null) secretReactor.setVisibility(View.GONE);
            if (mainLayout != null) mainLayout.setBackgroundColor(BLACK);
        }
    }

    // ---------- UI ----------

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildUI() {
        drawer = new DrawerLayout(this);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(BLACK);
        mainLayout = main;

        // Top bar: menu on the left, title in the middle (tap = settings menu).
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(10), dp(12), dp(4));

        TextView menu = new TextView(this);
        menu.setText("☰");
        menu.setTextColor(Color.WHITE);
        menu.setTextSize(24);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(dp(10), 0, dp(10), 0);
        menu.setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        top.addView(menu);

        TextView title = new TextView(this);
        title.setText("EXE AI");
        title.setTextColor(RED);
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setOnClickListener(v -> showHiddenApiSettings());
        title.setOnLongClickListener(v -> {
            showHiddenApiSettings();
            return true;
        });
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        main.addView(top);

        statusLine = new TextView(this);
        statusLine.setTextSize(11);
        statusLine.setPadding(dp(20), 0, dp(12), dp(4));
        main.addView(statusLine);

        scroll = new ScrollView(this);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(dp(10), dp(8), dp(10), dp(8));
        scroll.addView(chat);
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // Bottom bar: one rounded black bar. Left to right: file, photo, settings, text, voice, send.
        LinearLayout bottomWrap = new LinearLayout(this);
        bottomWrap.setPadding(dp(10), dp(6), dp(10), dp(12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setShape(GradientDrawable.RECTANGLE);
        barBg.setCornerRadius(dp(32));
        barBg.setColor(BLACK);
        barBg.setStroke(dp(1), Color.rgb(60, 60, 60));
        bar.setBackground(barBg);

        bar.addView(circleButton("📄", false, v -> pickFile(), v -> importKnowledge()));
        bar.addView(circleButton("🖼", false, v -> pickImage(), v -> openCamera()));
        bar.addView(circleButton("🔊", false, v -> showVoiceSettings(), null));

        input = new EditText(this);
        input.setHint("Mesaj yaz...");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff888888);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMaxLines(4);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        // Voice: tap = one question, long press = continuous voice chat on/off.
        bar.addView(circleButton("🎙", false, v -> listen(), v -> toggleLiveVoice()));
        bar.addView(circleButton("➤", true, v -> send(), null));

        bottomWrap.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        main.addView(bottomWrap);

        // Black base layer first, so the secret orb can sit between it and the normal screen.
        stageLayout = new FrameLayout(this);
        stageLayout.setBackgroundColor(BLACK);
        stageLayout.addView(main, new FrameLayout.LayoutParams(-1, -1));
        drawer.addView(stageLayout, new DrawerLayout.LayoutParams(-1, -1));

        // Left drawer: chat list.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL);
        panel.setPadding(dp(14), dp(20), dp(14), dp(14));

        TextView panelTitle = new TextView(this);
        panelTitle.setText("Sohbetler");
        panelTitle.setTextColor(RED);
        panelTitle.setTextSize(20);
        panelTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(panelTitle);

        Button newChat = new Button(this);
        newChat.setText("+ Yeni sohbet");
        newChat.setAllCaps(false);
        newChat.setTextColor(Color.WHITE);
        newChat.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffb00020));
        newChat.setOnClickListener(v -> newSession());
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(-1, -2);
        newLp.setMargins(0, dp(12), 0, dp(12));
        panel.addView(newChat, newLp);

        ScrollView listScroll = new ScrollView(this);
        sessionList = new LinearLayout(this);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(sessionList);
        panel.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        DrawerLayout.LayoutParams panelLp = new DrawerLayout.LayoutParams(dp(290), -1, Gravity.START);
        drawer.addView(panel, panelLp);

        setContentView(drawer);
    }

    /** Round button for the bottom bar. Tap and optional long press. */
    private TextView circleButton(String label, boolean filled, View.OnClickListener click,
                                  View.OnClickListener longClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(filled ? 0xffb00020 : BUTTON_BG);
        if (!filled) shape.setStroke(dp(1), RED);
        b.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        if (longClick != null) {
            b.setOnLongClickListener(v -> {
                longClick.onClick(v);
                return true;
            });
        }
        return b;
    }

    private void refreshStatus() {
        if (statusLine == null) return;
        boolean hasKey = !ApiKeyStore.getGeminiKey(this).isEmpty();
        statusLine.setText(hasKey ? "Gemini: bağlı" : "Gemini: anahtar yok (EXE AI başlığına bas)");
        statusLine.setTextColor(hasKey ? 0xff7fd17f : 0xffff8a80);
    }

    private void addMessage(String message, boolean user) {
        TextView t = new TextView(this);
        t.setText((user ? "SEN  ›  " : "EXE  ›  ") + message);
        t.setTextColor(user ? 0xffffb5b5 : Color.WHITE);
        t.setTextSize(15);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackgroundColor(user ? 0xff351316 : PANEL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(5));
        chat.addView(t, lp);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Shows an EXE AI reply, stores it in the current chat, and speaks it when voice is on. */
    private void reply(String text) {
        addMessage(text, false);
        db.addHistory(currentSession, "exe", text);
        if (tts != null && ttsReady && voiceEnabled && !secretMode) {
            tts.setSpeechRate(speechRate);
            tts.setPitch(speechPitch);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "exe_reply");
        } else if (liveVoice) {
            mainHandler.postDelayed(this::listen, 600);
        }
    }

    private void postReply(String text) {
        mainHandler.post(() -> reply(text));
    }

    private void hideKeyboard() {
        try {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    // ---------- chat sessions ----------

    private void openLatestSession() {
        List<DB.Session> all = db.sessions();
        if (all.isEmpty()) {
            newSession();
        } else {
            openSession(all.get(0).id);
        }
    }

    private void newSession() {
        currentSession = db.createSession("Yeni sohbet");
        chat.removeAllViews();
        applySecretMode(false);
        addMessage("EXE AI hazır efendim. Yazabilir, sesli konuşabilir, dosya ya da fotoğraf ekleyebilirsin.", false);
        drawer.closeDrawer(GravityCompat.START);
        refreshSessionList();
    }

    private void openSession(long sessionId) {
        currentSession = sessionId;
        chat.removeAllViews();
        for (String[] row : db.loadSession(sessionId)) {
            addMessage(row[1], "user".equals(row[0]));
        }
        applySecretMode(prefs.getBoolean(orbKey(sessionId), false));
        drawer.closeDrawer(GravityCompat.START);
        refreshSessionList();
    }

    private void refreshSessionList() {
        if (sessionList == null) return;
        sessionList.removeAllViews();
        for (DB.Session s : db.sessions()) {
            TextView item = new TextView(this);
            item.setText(s.title);
            item.setTextSize(15);
            item.setTextColor(s.id == currentSession ? RED : Color.WHITE);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            item.setBackgroundColor(s.id == currentSession ? 0xff351316 : 0x00000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(2), 0, dp(2));
            item.setOnClickListener(v -> openSession(s.id));
            item.setOnLongClickListener(v -> {
                showSessionMenu(s);
                return true;
            });
            sessionList.addView(item, lp);
        }
    }

    /** Long press on a chat in the drawer: rename or delete. */
    private void showSessionMenu(DB.Session s) {
        new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setItems(new String[]{"Yeniden adlandır", "Sil"}, (d, which) -> {
                    if (which == 0) renameSession(s);
                    else deleteSession(s);
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void renameSession(DB.Session s) {
        EditText field = new EditText(this);
        field.setText(s.title);
        new AlertDialog.Builder(this)
                .setTitle("Yeni ad")
                .setView(field)
                .setPositiveButton("Kaydet", (d, w) -> {
                    String title = field.getText().toString().trim();
                    if (!title.isEmpty()) {
                        db.renameSession(s.id, title);
                        refreshSessionList();
                    }
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void deleteSession(DB.Session s) {
        new AlertDialog.Builder(this)
                .setMessage("\"" + s.title + "\" sohbeti silinsin mi? Bu işlem geri alınamaz.")
                .setPositiveButton("Sil", (d, w) -> {
                    db.deleteSession(s.id);
                    if (s.id == currentSession) {
                        openLatestSession();
                    } else {
                        refreshSessionList();
                    }
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    // ---------- message flow ----------

    private void send() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        input.setText("");
        hideKeyboard();
        addMessage(q, true);
        db.addHistory(currentSession, "user", q);
        db.nameSessionIfNew(currentSession, q);
        refreshSessionList();
        handleMessage(q);
    }

    /** Central routing: math, learning command, memory, reminders, device, local knowledge, then Gemini or web. */
    private void handleMessage(String raw) {
        if (raw.trim().equals("5087")) {
            setOrbForSession(true);
            reply("Animasyon açıldı efendim. Kapatmak için *animasyonkapat* yaz.");
            return;
        }
        String compact = raw.trim().toLowerCase(new Locale("tr", "TR")).replace("*", "").replace(" ", "");
        if (compact.equals("animasyonkapat")) {
            setOrbForSession(false);
            reply("Animasyonu kapattım efendim.");
            return;
        }
        if (isPlainMath(raw.trim())) {
            Double value = Calc.eval(raw.trim());
            if (value != null) {
                reply(Calc.format(value) + " efendim.");
                return;
            }
        }

        String learning = getLearningQuery(raw);
        if (learning != null) {
            if (learning.isEmpty()) {
                reply("Öğrenmek istediğin konuyu komuttan sonra yaz. Örnek: *** RAM nedir?");
                return;
            }
            addMessage("İnternette kaynak aranıyor efendim...", false);
            worker.execute(() -> researchAndLearn(learning));
            return;
        }

        String fixed = ChatNormalizer.fix(raw);
        String s = TextUnderstanding.normalize(fixed);

        if (handleMemoryCommand(raw, s)) return;
        if (handleWhatsAppCommand(raw, s)) return;
        if (handlePlanCommand(raw, s)) return;
        if (handleNoteCommand(raw, s)) return;
        if (handleReminderCommand(raw, s)) return;
        if (handleDeviceCommand(s)) return;

        if (TextUnderstanding.containsIntent(s, "gundem", "bugunun haberleri", "bugun haber", "haberleri oku", "haberleri ozetle")) {
            newsSummary();
            return;
        }

        String local = localAnswer(s);
        if (local != null) {
            reply(local);
            return;
        }

        String learned = db.findLearnedFuzzy(fixed);
        if (learned != null) {
            reply("Hafızamda kayıtlı yakın eşleşme:\n" + learned);
            return;
        }
        String qa = db.knowledgeFuzzy(fixed);
        if (qa != null) {
            reply(qa);
            return;
        }

        if (!ApiKeyStore.getGeminiKey(this).isEmpty()) {
            askGemini(raw);
            return;
        }
        if (isOnlineResearchAvailable()) {
            addMessage("Gemini anahtarı yok; internetten araştırıyorum efendim...", false);
            worker.execute(() -> researchAndLearn(fixed));
            return;
        }
        reply("Anlamadım efendim.");
    }

    /**
     * True for input that is only numbers and operators with at least one real operator, e.g. "2+2" or "3 * (4 - 1)".
     * A spaced minus is needed for subtraction, so dates like 21-11-2026 or phone numbers are not calculated.
     */
    private boolean isPlainMath(String text) {
        if (!text.matches("[0-9+\\-*/().,x×÷\\s]+") || !text.matches(".*\\d.*")) return false;
        return text.matches(".*[+*/×÷x].*") || text.matches(".*\\s-\\s.*");
    }

    private String getLearningQuery(String q) {
        String trimmed = q.trim();
        if (trimmed.startsWith("***") || trimmed.startsWith("###")) {
            return trimmed.substring(3).trim();
        }
        String lower = trimmed.toLowerCase(new Locale("tr", "TR"));
        if (lower.startsWith("öğren:") || lower.startsWith("ogren:")) {
            return trimmed.substring(trimmed.indexOf(':') + 1).trim();
        }
        return null;
    }

    private boolean isOnlineResearchAvailable() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) return false;
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void researchAndLearn(String rawQuery) {
        try {
            String query = TextUnderstanding.prepareSearchQuery(rawQuery);
            WebResearcher.Result result = WebResearcher.research(query);
            if (result == null || result.answer.trim().isEmpty()) {
                postReply("Anlamadım efendim. Hangi yönünü merak ettiğini söylersen daha iyi yardımcı olabilirim.");
                return;
            }
            db.saveLearned(rawQuery, result.answer, result.sources);
            postReply(result.answer + "\n\nKaynaklar:\n" + result.sources);
        } catch (Exception e) {
            postReply("Web araştırması sırasında hata oluştu, tekrar deneyebilirsin efendim.");
        }
    }

    // ---------- Gemini ----------

    private void askGemini(String raw) {
        String key = ApiKeyStore.getGeminiKey(this);
        Bitmap image = pendingBitmap;
        pendingBitmap = null;
        String fileText = pendingFileText;
        pendingFileText = null;

        StringBuilder prompt = new StringBuilder();
        List<String> history = db.recentHistory(currentSession, 6);
        Collections.reverse(history);
        if (!history.isEmpty()) {
            prompt.append("Önceki konuşma:\n");
            for (String h : history) prompt.append(h).append('\n');
            prompt.append('\n');
        }
        prompt.append("Kullanıcının mesajı (yazım hatası olabilir): ").append(raw);
        if (fileText != null) prompt.append("\n\n[Ekli dosya içeriği]\n").append(fileText);

        boolean complex = GeminiClient.isComplex(raw, image != null);
        // Questions longer than 50 characters may take up to 2 minutes; shorter ones get 20 seconds.
        long budget = raw.trim().length() > 50 ? GeminiClient.LONG_BUDGET_MS : GeminiClient.TOTAL_BUDGET_MS;

        worker.execute(() -> {
            try {
                String answer = GeminiClient.ask(key, complex, GeminiClient.SYSTEM_PROMPT, prompt.toString(), image, budget);
                postReply(answer);
            } catch (GeminiClient.GeminiException e) {
                postReply(e.getMessage());
            }
        });
    }

    /** Settings menu, opened by tapping or long-pressing the EXE AI title. */
    private void showHiddenApiSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(12), dp(22), dp(4));

        String saved = ApiKeyStore.getGeminiKey(this);
        TextView info = new TextView(this);
        info.setText("Gemini anahtarı şifreli depoda saklanır. Model otomatik seçilir.\n"
                + "Kayıtlı anahtar: " + (saved.isEmpty() ? "yok" : "•••• " + saved.substring(Math.max(0, saved.length() - 4))));
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(13);
        panel.addView(info);

        EditText keyInput = new EditText(this);
        keyInput.setHint("Gemini API anahtarı");
        keyInput.setTextColor(Color.WHITE);
        keyInput.setHintTextColor(0xff888888);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(keyInput);

        Button test = new Button(this);
        test.setText("Bağlantıyı test et");
        test.setAllCaps(false);
        test.setOnClickListener(v -> {
            String k = keyInput.getText().toString().trim();
            if (k.isEmpty()) k = ApiKeyStore.getGeminiKey(this);
            final String useKey = k;
            addMessage("Gemini bağlantısı test ediliyor...", false);
            worker.execute(() -> {
                try {
                    String r = GeminiClient.testConnection(useKey);
                    mainHandler.post(() -> { addMessage("Gemini testi başarılı: " + r, false); refreshStatus(); });
                } catch (GeminiClient.GeminiException e) {
                    mainHandler.post(() -> addMessage("Gemini testi başarısız: " + e.getMessage(), false));
                }
            });
        });
        panel.addView(test);

        Button clear = new Button(this);
        clear.setText("Kayıtlı anahtarı sil");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> {
            ApiKeyStore.clearGeminiKey(this);
            Toast.makeText(this, "Anahtar silindi.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        panel.addView(clear);

        Button update = new Button(this);
        update.setText("Güncelleme: APK dosyası seç ve kur");
        update.setAllCaps(false);
        update.setOnClickListener(v -> pickApk());
        panel.addView(update);

        new AlertDialog.Builder(this)
                .setTitle("EXE AI • Ayarlar")
                .setView(panel)
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    String newKey = keyInput.getText().toString().trim();
                    if (!newKey.isEmpty()) {
                        if (ApiKeyStore.saveGeminiKey(this, newKey)) {
                            Toast.makeText(this, "Anahtar güvenli şekilde kaydedildi.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Güvenli depo açılamadı; anahtar kaydedilmedi.", Toast.LENGTH_LONG).show();
                        }
                    }
                    refreshStatus();
                })
                .setNegativeButton("Kapat", null)
                .show();
    }

    // ---------- app update (APK) ----------

    /**
     * An installed app cannot rewrite its own code from a ZIP. The practical route is: build the APK
     * (GitHub Actions), pick the APK file here, and let Android's installer replace this app.
     */
    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive"});
        startActivityForResult(i, APK_PICK);
    }

    private void installApk(Uri uri) {
        worker.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Bu uygulamaya 'bilinmeyen kaynaklardan yükleme' izni ver, sonra tekrar dene.",
                                Toast.LENGTH_LONG).show();
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    });
                    return;
                }
                File dir = new File(getCacheDir(), "update");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Güncelleme klasörü oluşturulamadı");
                File apk = new File(dir, "exe_ai_update.apk");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(apk)) {
                    if (in == null) throw new IllegalStateException("APK dosyası okunamadı");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Uri shareUri = FileProvider.getUriForFile(this, FILE_PROVIDER, apk);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(shareUri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                mainHandler.post(() -> startActivity(install));
            } catch (Exception e) {
                postReply("Güncelleme başlatılamadı: " + safeError(e));
            }
        });
    }

    // ---------- today's news ----------

    /** Reads today's headlines. With a Gemini key the headlines are summarized; without one they are listed. */
    private void newsSummary() {
        String key = ApiKeyStore.getGeminiKey(this);
        worker.execute(() -> {
            try {
                List<News.Item> items = News.fetch(12);
                if (items.isEmpty()) {
                    postReply("Şu an güncel başlıklara ulaşamadım efendim. Biraz sonra tekrar dener misin?");
                    return;
                }
                StringBuilder list = new StringBuilder();
                for (News.Item item : items) list.append("- ").append(item.title).append('\n');
                String today = new SimpleDateFormat("d MMMM yyyy, EEEE", new Locale("tr", "TR")).format(new Date());

                if (key.isEmpty()) {
                    postReply("Bugünün başlıkları (" + today + "):\n" + list.toString().trim());
                    return;
                }
                String prompt = "Bugün " + today + ". Aşağıda bugünkü haber başlıkları var. "
                        + "Bunlardan bugünün gündemini Türkçe, en önemli 5-6 maddede özetle. "
                        + "Sadece başlıklardaki bilgileri kullan, başlıklarda olmayan bir şey ekleme.\n\nBaşlıklar:\n" + list;
                String answer = GeminiClient.ask(key, true, GeminiClient.SYSTEM_PROMPT, prompt, null,
                        GeminiClient.LONG_BUDGET_MS);
                postReply("Bugünün gündemi (" + today + "):\n" + answer);
            } catch (GeminiClient.GeminiException e) {
                postReply(e.getMessage());
            } catch (Exception e) {
                postReply("Gündem alınamadı efendim: " + safeError(e));
            }
        });
    }

    // ---------- memory ----------

    private boolean handleMemoryCommand(String raw, String s) {
        String low = raw.trim().toLowerCase(new Locale("tr", "TR"));
        if (low.startsWith("hatırla:") || low.startsWith("hatirla:")) {
            String fact = raw.substring(raw.indexOf(':') + 1).trim();
            if (fact.isEmpty()) {
                reply("Neyi hatırlamamı istediğini ':' işaretinden sonra yazabilirsin efendim.");
            } else {
                db.saveMemory(fact, "kullanici");
                reply("Kaydettim efendim: " + fact);
            }
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "hafizami listele", "hafizayi goster", "hafizami goster")) {
            List<String> items = db.listMemory(10);
            if (items.isEmpty()) reply("Hafızamda henüz kayıt yok efendim.");
            else reply("Son kayıtlar:\n• " + String.join("\n• ", items));
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "hafizayi sil", "hafizayi temizle")) {
            db.clearMemory();
            reply("Hafızadaki kayıtları sildim efendim.");
            return true;
        }
        return false;
    }

    // ---------- reminders and alarms ----------

    private boolean handleReminderCommand(String raw, String s) {
        boolean listRequest = TextUnderstanding.containsIntent(s, "alarmlari listele", "hatirlaticilari listele",
                "alarmlarimi goster", "hatirlaticilarimi goster");
        if (listRequest) {
            listReminders();
            return true;
        }

        Matcher del = Pattern.compile("(alarm|hatirlatici) sil (\\d+)").matcher(s);
        if (del.find()) {
            long id = Long.parseLong(del.group(2));
            if (db.reminderById(id) == null) {
                reply("Bu numaralı bir alarm bulamadım efendim. Önce alarmları listeleyebilirsin.");
            } else {
                Reminders.cancel(this, id);
                db.deleteReminder(id);
                reply("Alarmı sildim efendim.");
            }
            return true;
        }

        boolean mentionsAlarm = s.contains("alarm") || s.contains("hatirlat");
        if (!mentionsAlarm) return false;

        long due = Reminders.parseDue(s, System.currentTimeMillis());
        boolean asksToSet = TextUnderstanding.containsIntent(s, "kur", "ayarla", "hatirlat", "alarm kur");
        if (due < 0) {
            if (asksToSet) {
                reply("Saati anlayamadım efendim. Örnek: 'saat 18.30'a alarm kur' ya da '10 dakika sonra hatırlat'.");
                return true;
            }
            return false;
        }

        boolean repeat = s.contains("her gun") || s.contains("tekrar");
        String text = (s.contains("alarm") ? "Alarm: " : "Hatırlatıcı: ") + raw.trim();
        long id = db.addReminder(text, due, repeat);
        DB.Reminder created = db.reminderById(id);
        if (created == null || !Reminders.arm(this, created)) {
            reply("Zamanı geçmiş göründü efendim, kuramadım.");
            return true;
        }
        reply("Tamam efendim. " + Reminders.formatTime(due) + " için kurdum"
                + (repeat ? ", her gün tekrarlanacak" : "") + ". (No: " + id + ")");
        return true;
    }

    private void listReminders() {
        List<DB.Reminder> active = db.activeReminders();
        if (active.isEmpty()) {
            reply("Aktif alarm veya hatırlatıcı yok efendim.");
            return;
        }
        StringBuilder sb = new StringBuilder("Aktif alarm ve hatırlatıcılar:\n");
        for (DB.Reminder r : active) {
            sb.append("• No ").append(r.id).append(" — ").append(Reminders.formatTime(r.dueMillis))
              .append(r.repeatDaily ? " (her gün)" : "").append(" — ").append(r.text).append('\n');
        }
        sb.append("Silmek için: 'alarm sil <no>'.");
        reply(sb.toString());
    }

    // ---------- day plans and notes ----------

    /** Words that make a dated sentence a plan, e.g. "yarin Ankara'ya gidicem". */
    private static final String[] PLAN_WORDS = {
            "gidicem", "gidecegim", "gidecegiz", "yapicam", "yapacagim",
            "toplanti", "randevu", "plan", "etkinlik", "bulusma"
    };

    private boolean handlePlanCommand(String raw, String s) {
        if (TextUnderstanding.containsIntent(s, "bugunun plani")) {
            listPlans(startOfDay(0), startOfDay(1), "Bugünün planı");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "yarinin plani")) {
            listPlans(startOfDay(1), startOfDay(2), "Yarının planı");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "planlarimi listele", "planlarimi goster")) {
            listUpcomingPlans();
            return true;
        }

        boolean planWord = false;
        for (String w : PLAN_WORDS) {
            if (s.contains(w)) {
                planWord = true;
                break;
            }
        }
        if (!planWord || !Planner.hasDate(s)) return false;

        long due = Planner.parse(s, System.currentTimeMillis());
        if (due < 0) return false;
        if (due < System.currentTimeMillis() - 60_000L) {
            reply("Bu tarih geçmişte kalmış görünüyor efendim, planı kaydetmedim.");
            return true;
        }

        String text = raw.trim();
        db.addPlan(text, due);
        StringBuilder answer = new StringBuilder("Planı kaydettim efendim: ")
                .append(Reminders.formatTime(due)).append(".");

        // Notification only when the user asks for it explicitly.
        if (s.contains("hatirlat") || s.contains("bildirim")) {
            long id = db.addReminder("Plan: " + text, due, false);
            DB.Reminder created = db.reminderById(id);
            if (created != null && Reminders.arm(this, created)) {
                answer.append(" Bildirim de kurdum.");
            } else {
                answer.append(" Bildirimi kuramadım, zamanı geçmiş olabilir.");
            }
        }
        reply(answer.toString());
        return true;
    }

    private void listPlans(long from, long to, String title) {
        List<DB.Plan> plans = db.plansBetween(from, to);
        if (plans.isEmpty()) {
            reply(title + " için kayıtlı bir şey yok efendim.");
            return;
        }
        StringBuilder sb = new StringBuilder(title).append(":\n");
        for (DB.Plan p : plans) {
            sb.append("• ").append(Reminders.formatTime(p.dueMillis)).append(" — ").append(p.text).append('\n');
        }
        reply(sb.toString().trim());
    }

    private void listUpcomingPlans() {
        List<DB.Plan> plans = db.plansFrom(System.currentTimeMillis(), 20);
        if (plans.isEmpty()) {
            reply("Yaklaşan bir planın yok efendim.");
            return;
        }
        StringBuilder sb = new StringBuilder("Yaklaşan planların:\n");
        for (DB.Plan p : plans) {
            sb.append("• ").append(Reminders.formatTime(p.dueMillis)).append(" — ").append(p.text).append('\n');
        }
        reply(sb.toString().trim());
    }

    /** Midnight of today plus the given number of days. */
    private static long startOfDay(int addDays) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, addDays);
        return c.getTimeInMillis();
    }

    private boolean handleNoteCommand(String raw, String s) {
        if (TextUnderstanding.containsIntent(s, "notlarimi goster", "notlarimi listele")) {
            List<String> notes = db.recentNotes(20);
            if (notes.isEmpty()) {
                reply("Kayıtlı not yok efendim.");
            } else {
                StringBuilder sb = new StringBuilder("Son notların:\n");
                for (String n : notes) sb.append("• ").append(n).append('\n');
                reply(sb.toString().trim());
            }
            return true;
        }
        Matcher m = Pattern.compile("^not al:?\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(raw.trim());
        if (m.find()) {
            db.addNote(m.group(1).trim());
            reply("Notu kaydettim efendim.");
            return true;
        }
        return false;
    }

    // ---------- WhatsApp message (opens the chat, the user presses send) ----------

    private static final int CONTACTS_PERMISSION = 48;

    /** "whatsapp'tan Timu'ya merhaba yaz" → target "Timu", message "merhaba". */
    private static final Pattern WHATSAPP_CMD = Pattern.compile(
            "whatsapp\\S*\\s+(.+?)'?(?:ya|ye|a|e)?\\s+(.+?)\\s+(?:yaz|gönder|gonder|mesaj at)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private boolean handleWhatsAppCommand(String raw, String s) {
        if (!s.contains("whatsapp")) return false;
        Matcher m = WHATSAPP_CMD.matcher(raw.trim());
        if (!m.find()) return false;

        String target = m.group(1).trim();
        String message = m.group(2).trim().replaceFirst("^(?i)(ya|ye)\\s+", "");
        if (message.isEmpty()) {
            reply("Ne yazmamı istediğini anlayamadım efendim.");
            return true;
        }
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, CONTACTS_PERMISSION);
            reply("Rehbere bakabilmem için izin istedim efendim. İzni verdikten sonra komutu tekrar yaz.");
            return true;
        }

        String wanted = TextUnderstanding.normalize(target);
        String name = null;
        String phone = null;
        try (Cursor c = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                String candidate = c.getString(0);
                if (candidate != null && TextUnderstanding.normalize(candidate).contains(wanted)) {
                    name = candidate;
                    phone = c.getString(1);
                    break;
                }
            }
        } catch (SecurityException e) {
            reply("Rehbere erişemedim efendim. Uygulama izinlerinden rehbere izin ver.");
            return true;
        }
        if (name == null || phone == null) {
            reply("\"" + target + "\" adında bir kişi rehberde bulamadım efendim.");
            return true;
        }

        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("00")) digits = digits.substring(2);
        else if (digits.startsWith("0")) digits = "90" + digits.substring(1);
        else if (digits.length() == 10) digits = "90" + digits;

        final String number = digits;
        new AlertDialog.Builder(this)
                .setTitle("WhatsApp mesajı")
                .setMessage(name + " (" + phone + ") kişisine şunu yazayım mı?\n\n\"" + message
                        + "\"\n\nWhatsApp açılacak. Göndermek için sen gönder tuşuna basacaksın.")
                .setPositiveButton("Aç", (d, w) -> openWhatsApp(number, message))
                .setNegativeButton("Vazgeç", null)
                .show();
        return true;
    }

    private void openWhatsApp(String digits, String message) {
        try {
            String encoded = java.net.URLEncoder.encode(message, "UTF-8").replace("+", "%20");
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + encoded)));
        } catch (Exception e) {
            reply("WhatsApp'ı açamadım efendim.");
        }
    }

    // ---------- device control ----------

    private boolean handleDeviceCommand(String s) {
        if (TextUnderstanding.containsIntent(s, "sesi arttir", "sesi yukselt", "sesi artir")) {
            adjustVolume(AudioManager.ADJUST_RAISE);
            reply("Sesi artırdım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "sesi azalt", "sesi kis")) {
            adjustVolume(AudioManager.ADJUST_LOWER);
            reply("Sesi azalttım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "wifi ayarlari", "wifi ac", "wifiyi ac")) {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            reply("Wi-Fi ayarlarını açtım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "bluetooth ayarlari", "bluetooth ac", "bluetoothu ac")) {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            reply("Bluetooth ayarlarını açtım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "sistem ayarlari", "ayarlari ac")) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            reply("Sistem ayarlarını açtım efendim.");
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "on kamerayi ac", "on kamera ac", "selfie kamerasi ac")) {
            openInAppCamera(true);
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "kamerayi ac", "kamera ac")) {
            openInAppCamera(false);
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "kamerayi kapat", "kamera kapat")) {
            if (inAppCamera != null && inAppCamera.isOpen()) {
                inAppCamera.close();
                reply("Kamerayı kapattım efendim.");
            } else {
                reply("Açık bir kamera yok efendim.");
            }
            return true;
        }
        if (TextUnderstanding.containsIntent(s, "galeriyi ac", "galeri ac")) {
            Intent i = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            try {
                startActivity(i);
                reply("Galeriyi açmayı denedim efendim.");
            } catch (Exception e) {
                reply("Galeri uygulaması bulunamadı efendim.");
            }
            return true;
        }
        if (hasOpenVerb(s)) {
            String app = launchInstalledAppFromText(s);
            if (app != null) {
                reply(app + " uygulamasını açtım efendim.");
                return true;
            }
        }
        if (TextUnderstanding.containsIntent(s, "youtube ac", "youtubeyi ac")) { launchApp("youtube"); reply("YouTube'u açmayı denedim efendim."); return true; }
        if (TextUnderstanding.containsIntent(s, "minecraft ac", "minecrafti ac")) { launchApp("minecraft"); reply("Minecraft'ı açmayı denedim efendim."); return true; }
        if (TextUnderstanding.containsIntent(s, "chrome ac", "chromeu ac")) { launchApp("chrome"); reply("Chrome'u açmayı denedim efendim."); return true; }
        return false;
    }

    private void adjustVolume(int direction) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    /** Whole-word check: "kaç" must not count as the open verb "aç". */
    private boolean hasOpenVerb(String s) {
        for (String w : s.split(" ")) {
            if (isOpenVerb(w)) return true;
        }
        return false;
    }

    private boolean isOpenVerb(String w) {
        return w.equals("ac") || w.equals("baslat") || w.equals("calistir") || w.equals("programi");
    }

    /** Opens any installed launcher app whose visible name matches the text, with approximate spelling. */
    private String launchInstalledAppFromText(String normalized) {
        String[] words = normalized.split("\\s+");
        Intent query = new Intent(Intent.ACTION_MAIN, null);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(query, 0);
        ResolveInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ResolveInfo info : apps) {
            CharSequence labelCs = info.loadLabel(getPackageManager());
            if (labelCs == null) continue;
            String label = TextUnderstanding.normalize(labelCs.toString());
            if (label.length() < 2) continue;
            if ((" " + normalized + " ").contains(" " + label + " ")) { best = info; bestScore = 0; break; }
            for (int i = 0; i < words.length; i++) {
                for (int n = 1; n <= 4 && i + n <= words.length; n++) {
                    StringBuilder phrase = new StringBuilder();
                    boolean commandWord = false;
                    for (int j = i; j < i + n; j++) {
                        String w = words[j];
                        if (isOpenVerb(w) || w.equals("uygulama") || w.equals("lutfen")) { commandWord = true; break; }
                        if (phrase.length() > 0) phrase.append(' ');
                        phrase.append(w);
                    }
                    if (commandWord || phrase.length() == 0) continue;
                    String candidate = phrase.toString();
                    int distance = TextUnderstanding.distance(candidate, label);
                    if (label.contains(candidate) && candidate.length() >= 3) distance = 0;
                    int allowed = Math.max(1, Math.min(2, label.length() / 6));
                    if (distance <= allowed && distance < bestScore) { bestScore = distance; best = info; }
                }
            }
        }
        if (best == null) return null;
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setClassName(best.activityInfo.packageName, best.activityInfo.name);
        try {
            startActivity(launch);
            return best.loadLabel(getPackageManager()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void launchApp(String name) {
        String[] packages;
        if (name.equals("youtube")) packages = new String[]{"com.google.android.youtube"};
        else if (name.equals("minecraft")) packages = new String[]{"com.mojang.minecraftpe", "com.mojang.minecrafttrialpe"};
        else packages = new String[]{"com.android.chrome"};

        for (String p : packages) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) {
                startActivity(i);
                return;
            }
        }
        Toast.makeText(this, "Uygulama bulunamadı veya kurulu değil.", Toast.LENGTH_SHORT).show();
    }

    // ---------- local answers ----------

    private String localAnswer(String s) {
        if (TextUnderstanding.containsIntent(s, "seni kim yapti", "seni kim kodladi", "sen kimsin", "ai sen kimsin"))
            return "Ben EXE AI'yım efendim. Beni Aziz EXE kodladı.";
        if (TextUnderstanding.containsIntent(s, "adin ne", "ismin ne", "senin adin ne"))
            return "Benim adım EXE AI efendim. Kişisel yapay zekâ asistanınım.";
        if (TextUnderstanding.containsIntent(s, "merhaba", "selam", "iyi gunler", "gunaydin"))
            return "Merhaba efendim! Bugün ne yapalım?";
        if (TextUnderstanding.containsIntent(s, "nasilsin", "naber", "ne haber"))
            return "İyiyim efendim, teşekkür ederim. Sen nasılsın?";
        if (TextUnderstanding.containsIntent(s, "tesekkur", "sagol", "eyvallah"))
            return "Rica ederim efendim! Başka bir konuda da yardımcı olabilirim.";
        if (TextUnderstanding.containsIntent(s, "saat kac", "saat kacta"))
            return "Cihaz saati: " + new SimpleDateFormat("HH:mm", new Locale("tr", "TR")).format(new Date()) + " efendim.";
        if (TextUnderstanding.containsIntent(s, "komik", "espiri yap", "saka yap", "beni guldur")) {
            prefs.edit().putBoolean("comedy_mode", true).apply();
            return nextJoke();
        }
        if (prefs.getBoolean("comedy_mode", false) && TextUnderstanding.containsIntent(s, "devam", "baska", "daha")) return nextJoke();
        if (TextUnderstanding.containsIntent(s, "espiri modu kapat", "normal moda gec")) {
            prefs.edit().putBoolean("comedy_mode", false).apply();
            return "Espiri modunu kapattım efendim. Normal sohbete döndüm.";
        }
        if (TextUnderstanding.containsIntent(s, "yardim", "help", "neler yapabilirsin"))
            return "Komutlar efendim:\n• *** konu — internetten öğren\n• hatırla: bilgi — hafızaya kaydet\n"
                 + "• saat 18.30'a alarm kur / 10 dakika sonra hatırlat / her gün tekrar\n"
                 + "• alarmları listele / alarm sil <no>\n• wifi ayarlarını aç / sesi artır\n"
                 + "• uygulama adı + aç — uygulama başlat\n• komik — espiri modu\n"
                 + "• Mikrofon düğmesine uzun bas: sürekli sesli sohbet. Üstteki EXE AI yazısı ayarları açar.";
        return null;
    }

    private String nextJoke() {
        String[] jokes = {
                "Bilgisayar neden doktora gitmiş? Çünkü virüs kapmış! 😂",
                "Matematik kitabı neden üzgünmüş? Çünkü çok fazla problemi varmış! 😄",
                "Telefon neden gözlük takmış? Çünkü rehberindeki isimleri daha net görmek istemiş! 🤓",
                "Programcı neden denize girmemiş? Çünkü çok fazla bug varmış! 🐛",
                "Wi-Fi neden saklambaçta iyiymiş? Çünkü herkes onu arıyormuş! 😂"
        };
        int index = prefs.getInt("joke_index", 0);
        prefs.edit().putInt("joke_index", index + 1).apply();
        return jokes[index % jokes.length];
    }

    // ---------- voice ----------

    private void toggleLiveVoice() {
        liveVoice = !liveVoice;
        Toast.makeText(this, liveVoice ? "Sürekli sesli sohbet açık." : "Sürekli sesli sohbet kapalı.", Toast.LENGTH_SHORT).show();
        if (liveVoice) listen();
    }

    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "EXE AI'ye konuş");
        try {
            startActivityForResult(i, SPEECH);
        } catch (Exception e) {
            liveVoice = false;
            addMessage("Bu cihazda konuşma tanıma servisi bulunamadı.", false);
        }
    }

    /** Removes a leading "EXE" or "Hey EXE" from a recognized phrase. Returns the rest, possibly empty. */
    private String stripWakeWord(String heard) {
        Matcher m = WAKE_WORD.matcher(heard.trim());
        return m.find() ? heard.trim().substring(m.end()) : heard.trim();
    }

    private void showVoiceSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(12), dp(22), dp(4));

        CheckBox voiceCheck = new CheckBox(this);
        voiceCheck.setText("EXE AI sesli cevap versin");
        voiceCheck.setTextColor(Color.WHITE);
        voiceCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(RED));
        voiceCheck.setChecked(voiceEnabled);
        panel.addView(voiceCheck);

        TextView voiceLabel = new TextView(this);
        voiceLabel.setText("Ses seçimi (cihazda yüklü sesler)");
        voiceLabel.setTextColor(Color.LTGRAY);
        voiceLabel.setPadding(0, dp(12), 0, dp(4));
        panel.addView(voiceLabel);

        List<Voice> available = getAvailableVoices();
        List<String> names = new ArrayList<>();
        names.add("Varsayılan Türkçe sesi");
        for (Voice v : available) names.add(v.getName() + " (" + v.getLocale().toLanguageTag() + ")");
        Spinner voiceSpinner = new Spinner(this);
        voiceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int selected = 0;
        for (int x = 0; x < available.size(); x++) {
            if (available.get(x).getName().equals(selectedVoiceName)) selected = x + 1;
        }
        voiceSpinner.setSelection(selected);
        panel.addView(voiceSpinner);

        TextView rateLabel = new TextView(this);
        rateLabel.setText("Konuşma hızı");
        rateLabel.setTextColor(Color.LTGRAY);
        rateLabel.setPadding(0, dp(12), 0, dp(2));
        panel.addView(rateLabel);
        SeekBar rateBar = new SeekBar(this);
        rateBar.setMax(15);
        rateBar.setProgress(Math.max(0, Math.min(15, Math.round((speechRate - 0.5f) * 10))));
        panel.addView(rateBar);

        TextView pitchLabel = new TextView(this);
        pitchLabel.setText("Ses tonu");
        pitchLabel.setTextColor(Color.LTGRAY);
        pitchLabel.setPadding(0, dp(8), 0, dp(2));
        panel.addView(pitchLabel);
        SeekBar pitchBar = new SeekBar(this);
        pitchBar.setMax(15);
        pitchBar.setProgress(Math.max(0, Math.min(15, Math.round((speechPitch - 0.5f) * 10))));
        panel.addView(pitchBar);

        Button sample = new Button(this);
        sample.setText("Örnek konuşmayı dinle");
        sample.setAllCaps(false);
        sample.setOnClickListener(v -> {
            if (tts != null && ttsReady) {
                tts.setSpeechRate(0.5f + rateBar.getProgress() / 10.0f);
                tts.setPitch(0.5f + pitchBar.getProgress() / 10.0f);
                tts.speak("Merhaba efendim, ben EXE AI. Sesim böyle olacak.", TextToSpeech.QUEUE_FLUSH, null, "exe_sample");
            }
        });
        panel.addView(sample);

        new AlertDialog.Builder(this)
                .setTitle("EXE AI • Ses ayarları")
                .setView(panel)
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    voiceEnabled = voiceCheck.isChecked();
                    speechRate = 0.5f + rateBar.getProgress() / 10.0f;
                    speechPitch = 0.5f + pitchBar.getProgress() / 10.0f;
                    selectedVoiceName = voiceSpinner.getSelectedItemPosition() == 0
                            ? "" : available.get(voiceSpinner.getSelectedItemPosition() - 1).getName();
                    prefs.edit()
                            .putBoolean("voice_enabled", voiceEnabled)
                            .putString("voice_name", selectedVoiceName)
                            .putFloat("speech_rate", speechRate)
                            .putFloat("speech_pitch", speechPitch)
                            .apply();
                    applySavedVoice();
                    if (!voiceEnabled && tts != null) tts.stop();
                    Toast.makeText(this, "Ses ayarları kaydedildi.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private List<Voice> getAvailableVoices() {
        List<Voice> result = new ArrayList<>();
        if (tts == null || !ttsReady || tts.getVoices() == null) return result;
        Set<Voice> all = tts.getVoices();
        for (Voice v : all) {
            if (v.getLocale() != null && "tr".equalsIgnoreCase(v.getLocale().getLanguage())) result.add(v);
        }
        if (result.isEmpty()) result.addAll(all);
        return result;
    }

    private void applySavedVoice() {
        if (tts == null || !ttsReady) return;
        tts.setSpeechRate(speechRate);
        tts.setPitch(speechPitch);
        if (!selectedVoiceName.isEmpty() && tts.getVoices() != null) {
            for (Voice v : tts.getVoices()) {
                if (v.getName().equals(selectedVoiceName)) {
                    try {
                        tts.setVoice(v);
                    } catch (Exception ignored) { }
                    break;
                }
            }
        } else {
            tts.setLanguage(new Locale("tr", "TR"));
        }
    }

    // ---------- photo, camera, files ----------

    /** Camera button: opens the preview inside EXE AI. */
    private void openCamera() {
        openInAppCamera(false);
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, IMAGE_PICK);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/tab-separated-values", "text/csv", "text/markdown", "application/json"});
        startActivityForResult(i, FILE_PICK);
    }

    private void importKnowledge() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, IMPORT_QA);
    }

    /** The photo is kept and sent to Gemini with the next question. Its text is not read out. */
    private void keepPhoto(Bitmap bitmap) {
        pendingBitmap = bitmap;
        reply("Fotoğrafı aldım efendim. Şimdi ne sormak istediğini yazabilirsin.");
    }

    private void processImage(Uri uri) {
        worker.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = in == null ? null : BitmapFactory.decodeStream(in);
                if (bitmap == null) throw new IllegalStateException("Görsel çözülemedi");
                mainHandler.post(() -> keepPhoto(bitmap));
            } catch (Exception e) {
                postReply("Görsel açılamadı: " + safeError(e));
            }
        });
    }

    private void processCameraBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            addMessage("Kameradan fotoğraf alınamadı. Tekrar deneyebilirsin.", false);
            return;
        }
        keepPhoto(bitmap);
    }

    private void readAttachedFile(Uri uri) {
        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try (InputStream in = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && sb.length() < MAX_FILE_CHARS) {
                    sb.append(line).append('\n');
                }
                pendingFileText = sb.length() > MAX_FILE_CHARS ? sb.substring(0, MAX_FILE_CHARS) : sb.toString();
                postReply("Dosyayı ekledim efendim. Şimdi bu dosya hakkında ne sormak istediğini yazabilirsin.");
            } catch (Exception e) {
                postReply("Dosya okunamadı: " + safeError(e));
            }
        });
    }

    private void importTsv(Uri uri) {
        worker.execute(() -> {
            int imported = 0;
            int skipped = 0;
            android.database.sqlite.SQLiteDatabase writable = db.getWritableDatabase();
            writable.beginTransaction();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Dosya okunamadı");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\t", 3);
                    if (parts.length < 2) { skipped++; continue; }
                    String question = parts[0].trim();
                    String answer = parts[1].trim();
                    String category = parts.length >= 3 ? parts[2].trim() : "genel";
                    if (question.isEmpty() || answer.isEmpty()) { skipped++; continue; }
                    db.putQa(writable, question, answer, category);
                    imported++;
                }
                writable.setTransactionSuccessful();
            } catch (Exception e) {
                postReply("Bilgi paketi içe aktarılamadı: " + safeError(e));
                return;
            } finally {
                if (writable.inTransaction()) writable.endTransaction();
            }
            final int ok = imported;
            final int bad = skipped;
            postReply("Bilgi paketi tamamlandı. Eklenen/güncellenen soru: " + ok + ". Atlanan satır: " + bad
                    + ". Toplam bilgi kaydı: " + db.qaCount() + ".");
        });
    }

    private String safeError(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFY_PERMISSION && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            addMessage("Bildirim izni verilmedi; alarmlar çalışsa da bildirim gösterilemeyebilir.", false);
        }
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openInAppCamera(pendingCameraFront);
            } else {
                addMessage("Kamera izni verilmedi efendim.", false);
            }
        }
    }

    // ---------- in-app camera ----------

    private static final int CAMERA_PERMISSION = 49;
    private InAppCamera inAppCamera = null;
    private boolean pendingCameraFront = false;

    /** Opens the camera preview inside EXE AI. front = true uses the front camera. */
    private void openInAppCamera(boolean front) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraFront = front;
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        if (inAppCamera == null) {
            inAppCamera = new InAppCamera(this, new InAppCamera.Listener() {
                @Override
                public void onPhoto(Bitmap bitmap) {
                    keepPhoto(bitmap);
                }

                @Override
                public void onError(String message) {
                    addMessage(message, false);
                }
            });
        }
        inAppCamera.open(front);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPEECH) {
            if (resultCode != RESULT_OK || data == null) {
                liveVoice = false;
                return;
            }
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results == null || results.isEmpty()) return;
            String command = stripWakeWord(results.get(0));
            if (command.isEmpty()) {
                reply("Evet efendim?");
                return;
            }
            addMessage(command, true);
            db.addHistory(currentSession, "user", command);
            db.nameSessionIfNew(currentSession, command);
            refreshSessionList();
            handleMessage(command);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMAGE_PICK) {
            processImage(uri);
        } else if (requestCode == CAMERA) {
            android.os.Bundle extras = data.getExtras();
            Bitmap bitmap = extras == null ? null : (Bitmap) extras.get("data");
            processCameraBitmap(bitmap);
        } else if (requestCode == FILE_PICK) {
            readAttachedFile(uri);
        } else if (requestCode == IMPORT_QA) {
            importTsv(uri);
        } else if (requestCode == APK_PICK) {
            installApk(uri);
        }
    }
}
