package de.crafterteam.bungeesystem.chat;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.core.PlayerManager;
import de.crafterteam.bungeesystem.punish.PunishManager;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-Mod: Verstöße im Chat (Filter, Spam, Caps) geben Strike-Punkte. Wer innerhalb des Zeitfensters die Grenze erreicht,
 * wird automatisch gemutet – mit steigender Dauer bei jedem weiteren Auto-Mute (automod.mute-durations).
 */
public final class AutoMod {

    private final BungeeSystem plugin;
    /** Zeitpunkte & Punkte der Strikes pro Spieler (gleitendes Fenster). */
    private final Map<UUID, Deque<long[]>> strikes = new ConcurrentHashMap<>();
    /** Anzahl Auto-Mutes in dieser Proxy-Sitzung (für die Eskalation). */
    private final Map<UUID, Integer> level = new ConcurrentHashMap<>();

    public AutoMod(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Configuration cfg() {
        return plugin.config().getSection("automod");
    }

    /** Verstoß melden. type: filter | spam | caps */
    public void strike(ProxiedPlayer p, String type) {
        if (!cfg().getBoolean("enabled", true) || !plugin.moduleActive("chat")) return;
        if (p.hasPermission("bungeesystem.automod.bypass")) return;
        int points = cfg().getInt("points." + type, type.equals("filter") ? 3 : 1);
        if (points <= 0) return;
        long now = System.currentTimeMillis();
        long window = cfg().getLong("window-minutes", 30) * 60_000L;
        int limit = Math.max(1, cfg().getInt("mute-at-points", 9));
        Deque<long[]> q = strikes.computeIfAbsent(p.getUniqueId(), u -> new ArrayDeque<>());
        int total;
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst()[0] < now - window) q.pollFirst();
            q.addLast(new long[]{now, points});
            total = q.stream().mapToInt(x -> (int) x[1]).sum();
            if (total < limit) {
                if (total >= limit - points && total * 2 >= limit) plugin.messages().send(p, "automod.warning", "points", Integer.toString(total),
                        "limit", Integer.toString(limit));
                return;
            }
            q.clear();
        }
        mute(p);
    }

    private void mute(ProxiedPlayer p) {
        if (plugin.punish().mute(p.getUniqueId()) != null) return;
        List<String> durations = cfg().getStringList("mute-durations");
        if (durations.isEmpty()) durations = List.of("10m", "1h", "6h", "1d");
        int lvl = level.merge(p.getUniqueId(), 1, Integer::sum);
        long dur = Time.parse(durations.get(Math.min(lvl, durations.size()) - 1));
        if (dur == -2) dur = 600_000L;
        var known = new PlayerManager.Known(p.getUniqueId(), p.getName(), PlayerManager.ip(p));
        String reason = plugin.messages().raw("automod.reason");
        plugin.punish().punish(ProxyServer.getInstance().getConsole(), known, PunishManager.Type.MUTE, null, reason, dur)
                .thenAccept(x -> plugin.notifier().notify("chat", "automod.notify", "player", p.getName(), "duration", Time.format(x.until() - x.created()),
                        "level", Integer.toString(lvl)));
    }

    public void forget(UUID uuid) {
        strikes.remove(uuid);
    }
}
