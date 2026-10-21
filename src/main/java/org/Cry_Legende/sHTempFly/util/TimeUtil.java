package org.Cry_Legende.sHTempFly.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeUtil {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([a-zA-Z]*)");

    private TimeUtil() {
    }

    /**
     * Parse une duree du type "30m", "1h30m", "2j", "3600" (secondes par defaut).
     *
     * @return la duree en secondes, ou -1 si l'entree est invalide.
     */
    public static long parseSeconds(String input) {
        if (input == null || input.isBlank()) {
            return -1;
        }
        String cleaned = input.trim().toLowerCase();
        Matcher matcher = TOKEN.matcher(cleaned);
        long total = 0;
        int consumed = 0;
        boolean found = false;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                return -1;
            }
            consumed = matcher.end();
            long value;
            try {
                value = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ex) {
                return -1;
            }
            long multiplier = switch (matcher.group(2)) {
                case "", "s", "sec", "secs", "second", "seconds", "seconde", "secondes" -> 1L;
                case "m", "min", "mins", "minute", "minutes" -> 60L;
                case "h", "hour", "hours", "heure", "heures" -> 3600L;
                case "d", "day", "days", "j", "jour", "jours" -> 86400L;
                default -> -1L;
            };
            if (multiplier < 0) {
                return -1;
            }
            total += value * multiplier;
            found = true;
        }
        if (!found || consumed != cleaned.length()) {
            return -1;
        }
        return total;
    }

    public static long days(long seconds) {
        return seconds / 86400L;
    }

    public static long hours(long seconds) {
        return (seconds % 86400L) / 3600L;
    }

    public static long minutes(long seconds) {
        return (seconds % 3600L) / 60L;
    }

    public static long seconds(long seconds) {
        return seconds % 60L;
    }
}
