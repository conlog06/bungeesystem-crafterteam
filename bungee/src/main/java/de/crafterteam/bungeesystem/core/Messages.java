package de.crafterteam.bungeesystem.core;

import de.crafterteam.bungeesystem.util.Text;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;

import java.util.List;

/**
 * Alle Texte aus der messages.yml. Schlüssel "ban.success" → {prefix} wird durch prefix.ban ersetzt
 * (sonst prefix.default). Listen = mehrere Zeilen.
 */
public final class Messages {

    private final YamlFile file;

    public Messages(YamlFile file) {
        this.file = file;
    }

    private Configuration c() {
        return file.get();
    }

    public String prefix(String module) {
        String p = c().getString("prefix." + module, null);
        return p != null ? p : c().getString("prefix.default", "&8[&bSystem&8] &r");
    }

    /** Roh-Text mit Platzhaltern (ohne Farbumwandlung). */
    public String raw(String key, String... ph) {
        Object o = c().get(key);
        String s;
        if (o instanceof List<?> list) s = String.join("\n", list.stream().map(String::valueOf).toList());
        else if (o == null) s = "&cFehlender Text: " + key;
        else s = String.valueOf(o);
        int dot = key.indexOf('.');
        s = s.replace("{prefix}", prefix(dot > 0 ? key.substring(0, dot) : "default"));
        return Text.replace(s, escapeUser(ph));
    }

    /** Platzhalter mit Spieler-Text dürfen keine Farbcodes oder Klick-Buttons einschleusen. */
    private static final java.util.Set<String> USER_KEYS = java.util.Set.of("message", "reason");

    private static String[] escapeUser(String[] ph) {
        String[] out = ph.clone();
        for (int i = 0; i + 1 < out.length; i += 2) {
            if (USER_KEYS.contains(out[i]) && out[i + 1] != null) out[i + 1] = escape(out[i + 1]);
        }
        return out;
    }

    public static String escape(String s) {
        return s.replace("&", "&​").replace("](", "]​(").replace("\n", " ");
    }

    public boolean has(String key) {
        return c().contains(key) && !String.valueOf(c().get(key)).isEmpty();
    }

    /** Fertiger Text als Legacy-String (z. B. für Kick-Screens). */
    public String legacy(String key, String... ph) {
        return Text.color(raw(key, ph));
    }

    public BaseComponent[] component(String key, String... ph) {
        return Text.parse(raw(key, ph));
    }

    public void send(CommandSender to, String key, String... ph) {
        if (to == null || !has(key)) return;
        sendRaw(to, raw(key, ph));
    }

    /** Text (mit Zeilenumbrüchen) direkt senden. */
    public static void sendRaw(CommandSender to, String text) {
        if (to == null || text == null || text.isEmpty()) return;
        for (String line : text.split("\n", -1)) to.sendMessage(Text.parse(line));
    }

    public void actionbar(ProxiedPlayer p, String key, String... ph) {
        if (p == null || !has(key)) return;
        p.sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(legacy(key, ph)));
    }
}
