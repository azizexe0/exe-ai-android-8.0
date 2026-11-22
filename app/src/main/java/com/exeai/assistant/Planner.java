package com.exeai.assistant;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a day or date from a Turkish sentence and turns it into a due time.
 * Input must be normalized text (lowercase, no Turkish letters, see TextUnderstanding.normalize).
 * Examples: "1 ay sonra", "2 gun sonra", "yarin", "obur gun", "gelecek hafta", "23 aralik", "saat 14".
 */
public final class Planner {
    private static final Pattern RELATIVE = Pattern.compile("(\\d+) (gun|hafta|ay|yil) sonra");
    private static final Pattern WORD_DAY = Pattern.compile("(bugun|yarin|obur gun|gelecek hafta)");
    private static final Pattern CALENDAR = Pattern.compile("(\\d{1,2}) (ocak|subat|mart|nisan|mayis|haziran|temmuz|agustos|eylul|ekim|kasim|aralik)");
    private static final Pattern TIME = Pattern.compile("saat (\\d{1,2})(?: (\\d{2}))?");
    private static final String[] MONTHS = {
            "ocak", "subat", "mart", "nisan", "mayis", "haziran",
            "temmuz", "agustos", "eylul", "ekim", "kasim", "aralik"
    };
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int DEFAULT_HOUR = 9;

    private Planner() {
    }

    /** True if the sentence names a day or a calendar date. */
    public static boolean hasDate(String s) {
        return RELATIVE.matcher(s).find() || WORD_DAY.matcher(s).find() || CALENDAR.matcher(s).find();
    }

    /**
     * Due time in millis, or -1 when no day or date is found. Without a clock time it uses 09:00.
     * A calendar date that already passed this year is moved to next year.
     */
    public static long parse(String s, long nowMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMillis);
        boolean found = false;

        Matcher m = RELATIVE.matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            if ("gun".equals(unit)) c.add(Calendar.DAY_OF_YEAR, n);
            else if ("hafta".equals(unit)) c.add(Calendar.WEEK_OF_YEAR, n);
            else if ("ay".equals(unit)) c.add(Calendar.MONTH, n);
            else c.add(Calendar.YEAR, n);
            found = true;
        } else if ((m = WORD_DAY.matcher(s)).find()) {
            String word = m.group(1);
            if ("yarin".equals(word)) c.add(Calendar.DAY_OF_YEAR, 1);
            else if ("obur gun".equals(word)) c.add(Calendar.DAY_OF_YEAR, 2);
            else if ("gelecek hafta".equals(word)) c.add(Calendar.WEEK_OF_YEAR, 1);
            found = true;
        } else if ((m = CALENDAR.matcher(s)).find()) {
            int day = Integer.parseInt(m.group(1));
            int month = monthIndex(m.group(2));
            if (month < 0 || day < 1 || day > 31) return -1;
            c.set(Calendar.DAY_OF_MONTH, 1);       // avoids overflow when the current day is 31
            c.set(Calendar.MONTH, month);
            c.set(Calendar.DAY_OF_MONTH, day);
            if (c.getTimeInMillis() < nowMillis - DAY_MILLIS) c.add(Calendar.YEAR, 1);
            found = true;
        }
        if (!found) return -1;

        int hour = DEFAULT_HOUR;
        int minute = 0;
        Matcher t = TIME.matcher(s);
        if (t.find()) {
            int h = Integer.parseInt(t.group(1));
            if (h >= 0 && h <= 23) {
                hour = h;
                minute = t.group(2) == null ? 0 : Integer.parseInt(t.group(2));
            }
        }
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static int monthIndex(String name) {
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(name)) return i;
        }
        return -1;
    }
}
