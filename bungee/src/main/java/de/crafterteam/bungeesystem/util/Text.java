package de.crafterteam.bungeesystem.util;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Farben & Klick-Buttons für Nachrichten.
 * <ul>
 *   <li>{@code &a}, {@code &l} … klassische Farbcodes</li>
 *   <li>{@code &#00d2ff} Hex-Farben</li>
 *   <li>{@code [Text](/befehl)} klickbarer Button (Hover zeigt den Befehl)</li>
 *   <li>{@code [Text](/befehl|Hover-Text)} mit eigenem Hover</li>
 *   <li>{@code [Text](suggest:/befehl )} schreibt den Befehl nur in die Chatzeile</li>
 * </ul>
 */
public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern BUTTON = Pattern.compile("\\[((?:[^\\[\\]]|\\[[^\\]]*])+)]\\(([^)|]+)(?:\\|([^)]*))?\\)");

    private Text() {
    }

    public static String color(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(ChatColor.of("#" + m.group(1)).toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    /** Letzte Farbe + Formatierungen eines Legacy-Strings (§x§r§r…-Hex wird mitgenommen). */
    public static String lastColors(String s) {
        StringBuilder fmt = new StringBuilder();
        String color = "";
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) != ChatColor.COLOR_CHAR) continue;
            char c = Character.toLowerCase(s.charAt(i + 1));
            if (c == 'x' && i + 13 < s.length()) {
                color = s.substring(i, i + 14);
                fmt.setLength(0);
                i += 13;
            } else if ("0123456789abcdef".indexOf(c) >= 0) {
                color = s.substring(i, i + 2);
                fmt.setLength(0);
                i++;
            } else if ("klmno".indexOf(c) >= 0) {
                fmt.append(s, i, i + 2);
                i++;
            } else if (c == 'r') {
                color = "";
                fmt.setLength(0);
                i++;
            }
        }
        return color + fmt;
    }

    public static String strip(String s) {
        return ChatColor.stripColor(color(s));
    }

    /** Text mit Farben + Buttons → Komponenten. */
    public static BaseComponent[] parse(String raw) {
        if (raw == null) raw = "";
        List<BaseComponent> out = new ArrayList<>();
        Matcher m = BUTTON.matcher(raw);
        int last = 0;
        String carry = "";
        while (m.find()) {
            String before = carry + raw.substring(last, m.start());
            addLegacy(out, before);
            carry = lastColors(color(before));
            String label = m.group(1), action = m.group(2).trim(), hover = m.group(3);
            for (BaseComponent c : TextComponent.fromLegacyText(color(label))) {
                if (action.startsWith("suggest:")) {
                    c.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, action.substring(8)));
                } else if (action.startsWith("url:")) {
                    c.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, action.substring(4)));
                } else {
                    c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, action));
                }
                String h = hover != null && !hover.isBlank() ? hover : "&7" + action.replaceFirst("^(suggest|url):", "");
                c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new net.md_5.bungee.api.chat.hover.content.Text(TextComponent.fromLegacyText(color(h)))));
                out.add(c);
            }
            last = m.end();
        }
        addLegacy(out, carry + raw.substring(last));
        return out.toArray(new BaseComponent[0]);
    }

    private static void addLegacy(List<BaseComponent> out, String s) {
        if (s.isEmpty()) return;
        for (BaseComponent c : TextComponent.fromLegacyText(color(s))) out.add(c);
    }

    /** Platzhalter {key} ersetzen – Paare: key, value, key, value … */
    public static String replace(String s, String... ph) {
        if (s == null) return "";
        for (int i = 0; i + 1 < ph.length; i += 2) {
            s = s.replace("{" + ph[i] + "}", ph[i + 1] == null ? "" : ph[i + 1]);
        }
        return s;
    }

    /** Ähnlichkeit zweier Texte in Prozent (Levenshtein). */
    public static int similarity(String a, String b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 100;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return (int) Math.round((1.0 - (double) prev[b.length()] / max) * 100);
    }
}
