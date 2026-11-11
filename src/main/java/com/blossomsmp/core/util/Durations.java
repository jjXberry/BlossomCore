package com.blossomsmp.core.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads times like "30m", "12h", "3d", "1d 12h" or "once" from the config, and shows them nicely. */
public final class Durations {

    /** A kit with this cooldown can only ever be claimed one time. */
    public static final long ONCE = -1L;

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([smhdw])");

    private Durations() {
    }

    /** @return milliseconds, {@link #ONCE} for "once", 0 for no cooldown. */
    public static long parse(String text) {
        if (text == null) {
            return 0L;
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("0") || s.equals("none")) {
            return 0L;
        }
        if (s.equals("once") || s.equals("one-time") || s.equals("onetime")) {
            return ONCE;
        }
        if (s.matches("\\d+")) {
            return Long.parseLong(s) * 1000L; // a plain number means seconds
        }
        Matcher m = PART.matcher(s);
        long total = 0L;
        int found = 0;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            switch (m.group(2).charAt(0)) {
                case 's' -> total += n * 1000L;
                case 'm' -> total += n * 60_000L;
                case 'h' -> total += n * 3_600_000L;
                case 'd' -> total += n * 86_400_000L;
                case 'w' -> total += n * 604_800_000L;
                default -> { }
            }
            found++;
        }
        if (found == 0) {
            throw new IllegalArgumentException("'" + text + "' is not a time. Use something like 30m, 12h, 3d or once.");
        }
        return total;
    }

    /** 93_600_000 -> "1d 2h". Shows the two biggest parts. */
    public static String format(long millis) {
        if (millis <= 0L) {
            return "now";
        }
        long seconds = (millis + 999L) / 1000L;
        long d = seconds / 86_400L;
        long h = (seconds % 86_400L) / 3_600L;
        long m = (seconds % 3_600L) / 60L;
        long s = seconds % 60L;
        if (d > 0) {
            return h > 0 ? d + "d " + h + "h" : d + "d";
        }
        if (h > 0) {
            return m > 0 ? h + "h " + m + "m" : h + "h";
        }
        if (m > 0) {
            return s > 0 ? m + "m " + s + "s" : m + "m";
        }
        return s + "s";
    }
}
