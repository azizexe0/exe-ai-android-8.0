package com.exeai.assistant;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scheduling for alarms and reminders. Uses AlarmManager; receivers re-arm them after reboot. */
public final class Reminders {
    static final String CHANNEL_ID = "exe_reminders";
    static final String EXTRA_ID = "reminder_id";
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private Reminders() {}

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "EXE AI hatırlatıcılar",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Alarm ve hatırlatıcı bildirimleri");
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    /** Arms one reminder. Returns false when the time is already past. */
    public static boolean arm(Context context, DB.Reminder r) {
        if (r.dueMillis <= System.currentTimeMillis()) return false;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        PendingIntent pi = pendingFor(context, r.id);
        boolean exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (exactAllowed) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueMillis, pi);
        } else {
            // Exact alarms need the user's permission on Android 12+; fall back to a near-time alarm.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueMillis, pi);
        }
        return true;
    }

    public static void cancel(Context context, long id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingFor(context, id));
    }

    public static void armAll(Context context, DB db) {
        List<DB.Reminder> active = db.activeReminders();
        for (DB.Reminder r : active) arm(context, r);
    }

    static PendingIntent pendingFor(Context context, long id) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(context, (int) (id % Integer.MAX_VALUE), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void notifyUser(Context context, String text) {
        createChannel(context);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("EXE AI hatırlatıcı")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        try {
            androidx.core.app.NotificationManagerCompat.from(context)
                    .notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), b.build());
        } catch (SecurityException ignored) {
            // Notification permission was not granted on Android 13+.
        }
    }

    // ---------- Turkish time parsing ----------

    // Input is TextUnderstanding.normalize() output: punctuation such as '.' and ':' is already a space.
    private static final Pattern IN_MINUTES = Pattern.compile("(\\d+) (dakika|dk|saat) (sonra|icinde)");
    private static final Pattern AT_TIME = Pattern.compile("saat (\\d{1,2})(?: (\\d{2}))?(?: |$)");

    /** Result of parsing: due time in millis, or -1 if no time was found. */
    public static long parseDue(String normalizedText, long nowMillis) {
        Matcher m = IN_MINUTES.matcher(normalizedText);
        if (m.find()) {
            long amount = Long.parseLong(m.group(1));
            long unit = m.group(2).equals("saat") ? 60L * 60L * 1000L : 60L * 1000L;
            return nowMillis + amount * unit;
        }
        Matcher t = AT_TIME.matcher(normalizedText);
        if (!t.find()) return -1;
        int hour = Integer.parseInt(t.group(1));
        int minute = t.group(2) == null ? 0 : Integer.parseInt(t.group(2));
        if (hour > 23 || minute > 59) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMillis);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (normalizedText.contains("yarin")) c.add(Calendar.DAY_OF_MONTH, 1);
        else if (c.getTimeInMillis() <= nowMillis) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    public static String formatTime(long millis) {
        return new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("tr", "TR")).format(new Date(millis));
    }

    static long nextDay(long dueMillis) {
        return dueMillis + DAY_MILLIS;
    }
}
