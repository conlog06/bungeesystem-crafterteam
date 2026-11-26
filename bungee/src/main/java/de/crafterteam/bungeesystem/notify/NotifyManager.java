package de.crafterteam.bungeesystem.notify;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.core.PlayerManager;
import de.crafterteam.bungeesystem.util.Text;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.List;

/**
 * Team-Benachrichtigungen (Ban, Mute, Kick, Report, Chat-Filter). Jedes Teammitglied mit
 * bungeesystem.notify.&lt;typ&gt; bekommt sie – per /notify einzeln abschaltbar.
 */
public final class NotifyManager {

    public static final List<String> TYPES = List.of("ban", "mute", "kick", "report", "chat");

    private final BungeeSystem plugin;

    public NotifyManager(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    public boolean receives(ProxiedPlayer p, String type) {
        if (!p.hasPermission("bungeesystem.notify." + type) && !p.hasPermission("bungeesystem.notify.*")) return false;
        PlayerManager.Session s = plugin.players().session(p.getUniqueId());
        return s == null || !s.notifyOff.contains(type);
    }

    /** Nachricht aus messages.yml an alle Empfänger dieses Typs. */
    public void notify(String type, String key, String... ph) {
        String text = plugin.messages().raw(key, ph);
        for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
            if (receives(p, type)) Messages.sendRaw(p, text);
        }
        plugin.getLogger().info("[Notify/" + type + "] " + Text.strip(text.replace("\n", " ")));
        if (plugin.discord() != null) plugin.discord().onNotify(type, Text.strip(text).replace("\u200b", ""));
    }

    public void toggle(ProxiedPlayer p, String type) {
        PlayerManager.Session s = plugin.players().session(p);
        boolean nowOn;
        if (type.equals("all")) {
            boolean allOn = TYPES.stream().noneMatch(s.notifyOff::contains);
            if (allOn) s.notifyOff.addAll(TYPES);
            else s.notifyOff.clear();
            nowOn = !allOn;
        } else if (s.notifyOff.remove(type)) {
            nowOn = true;
        } else {
            s.notifyOff.add(type);
            nowOn = false;
        }
        plugin.players().saveSettings(s);
        plugin.messages().send(p, "notify.toggled", "type", type, "state", plugin.messages().raw(nowOn ? "general.on" : "general.off"));
    }
}
