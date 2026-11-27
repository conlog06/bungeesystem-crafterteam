package de.crafterteam.bungeesystem.core;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.punish.PunishManager;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Login (Ban-Prüfung inkl. IP), Join/Quit, Serverwechsel (Party folgt, Daten an den Spielserver). */
public final class ConnectionListener implements Listener {

    private final BungeeSystem plugin;

    public ConnectionListener(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(LoginEvent e) {
        if (e.isCancelled() || plugin.punish() == null) return;
        UUID uuid = e.getConnection().getUniqueId();
        String ip = e.getConnection().getSocketAddress() instanceof InetSocketAddress a && a.getAddress() != null
                ? a.getAddress().getHostAddress() : "";
        PunishManager.Punishment ban = plugin.punish().banFor(uuid, ip);
        if (ban == null) return;
        e.setCancelled(true);
        e.setCancelReason(TextComponent.fromLegacyText(plugin.punish().screen(ban)));
        if (!ban.uuid().equals(uuid)) {
            plugin.notifier().notify("ban", "ban.notify-ip", "player", e.getConnection().getName(), "banned", ban.name(), "id", "#" + ban.id());
        }
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent e) {
        ProxiedPlayer p = e.getPlayer();
        plugin.players().join(p);
        if (plugin.clans() != null) plugin.clans().refresh(p.getUniqueId());
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            if (!p.isConnected()) return;
            if (plugin.friends() != null) plugin.friends().announce(p, true);
            if (plugin.reports() != null) plugin.reports().remind(p);
            if (plugin.tickets() != null) plugin.tickets().remind(p);
        }, 2, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onSwitch(ServerSwitchEvent e) {
        ProxiedPlayer p = e.getPlayer();
        if (p.getServer() == null) return;
        ServerInfo server = p.getServer().getInfo();
        String id = p.getUniqueId().toString(), name = server.getName();
        long now = System.currentTimeMillis();
        plugin.db().run(con -> Database.exec(con, "update ls_players set online_server = ?, last_seen = ? where uuid = ?", name, now, id));
        if (plugin.parties() != null) plugin.parties().onSwitch(p, server);
        if (e.getFrom() != null && e.getFrom().getPlayers().isEmpty()) plugin.bridge().serverEmpty(e.getFrom().getName());
        // kurz warten, bis der Spieler auf dem Server "im Spiel" ist – dann Daten schicken
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            if (!p.isConnected() || p.getServer() == null || !p.getServer().getInfo().equals(server)) return;
            plugin.bridge().hello(p);
            plugin.bridge().pack(p);
            PlayerManager.Session s = plugin.players().session(p.getUniqueId());
            if (s != null) plugin.bridge().onlineTime(p, s.onlineTime());
            if (plugin.clans() != null) {
                for (ProxiedPlayer other : server.getPlayers()) {
                    String tag = plugin.clans().tag(other.getUniqueId());
                    if (tag != null) plugin.bridge().send(p, "CLANTAG", out -> {
                        out.writeUTF(other.getUniqueId().toString());
                        out.writeUTF(tag);
                    });
                }
            }
        }, 750, TimeUnit.MILLISECONDS);
    }

    @EventHandler
    public void onQuit(PlayerDisconnectEvent e) {
        ProxiedPlayer p = e.getPlayer();
        plugin.players().quit(p);
        if (plugin.friends() != null) {
            plugin.friends().announce(p, false);
            plugin.friends().forget(p.getUniqueId());
        }
        if (plugin.parties() != null) plugin.parties().onQuit(p);
        if (plugin.clans() != null) plugin.clans().forget(p.getUniqueId());
        if (plugin.chat() != null) plugin.chat().forget(p.getUniqueId());
        if (p.getServer() != null && p.getServer().getInfo().getPlayers().size() <= 1) plugin.bridge().serverEmpty(p.getServer().getInfo().getName());
    }
}
