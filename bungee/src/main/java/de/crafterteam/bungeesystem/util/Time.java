package de.crafterteam.bungeesystem.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Time {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(mo|y|w|d|h|m|s)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Berlin"));

    private Time() {
    }

    /**
     * "30d", "1h30m", "2w", "1mo", "perm" → Millisekunden. -1 = permanent, -2 = ungültig.
     */
    public static long parse(String in) {
        if (in == null || in.isBlank()) return -2;
        String s = in.trim().toLowerCase();
        if (s.equals("perm") || s.equals("permanent") || s.equals("-1") || s.equals("p")) return -1;
        Matcher m = PART.matcher(s);
        long total = 0;
        int consumed = 0;
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "y" -> v * 365L * 86_400_000L;
                case "mo" -> v * 30L * 86_400_000L;
                case "w" -> v * 7L * 86_400_000L;
                case "d" -> v * 86_400_000L;
                case "h" -> v * 3_600_000L;
                case "m" -> v * 60_000L;
                default -> v * 1000L;
            };
            consumed += m.group().replace(" ", "").length();
        }
        return consumed == s.replace(" ", "").length() && total > 0 ? total : -2;
    }

    public static boolean isDuration(String in) {
        return parse(in) != -2;
    }

    /** 93784000 → "1d 2h 3m" */
    public static String format(long ms) {
        if (ms < 0) return "Permanent";
        long s = ms / 1000, m = s / 60, h = m / 60, d = h / 24;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h % 24 > 0) sb.append(h % 24).append("h ");
        if (m % 60 > 0 && d == 0) sb.append(m % 60).append("m ");
        if (d == 0 && h == 0) sb.append(s % 60).append("s");
        return sb.toString().trim();
    }

    /** Onlinezeit: "12h 30m" bzw. "3d 4h" */
    public static String hours(long ms) {
        long m = ms / 60_000, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        return m + "m";
    }

    public static String date(long epoch) {
        return epoch <= 0 ? "-" : DATE.format(Instant.ofEpochMilli(epoch));
    }
}
