package de.crafterteam.bungeesystem.onlinetime;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.PlayerManager;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /onlinetime [Spieler] und /onlinetime top – Zeit wird netzwerkweit vom Proxy gezählt. */
public final class OnlineTimeCommands {

    private OnlineTimeCommands() {
    }

    public static void register(BungeeSystem plugin) {
        plugin.command("onlinetime", n -> new OnlineCmd(plugin, n));
    }

    /** Alle 60 s: Onlinezeit an den Server des Spielers (Scoreboard im LobbySystem). */
    public static void push(BungeeSystem plugin) {
        for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
            PlayerManager.Session s = plugin.players().session(p.getUniqueId());
            if (s != null && s.loaded) plugin.bridge().onlineTime(p, s.onlineTime());
        }
    }

    record Entry(String name, long time) {
    }

    static final class OnlineCmd extends Cmd {
        OnlineCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.onlinetime");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length > 0 && (args[0].equalsIgnoreCase("top") || args[0].equalsIgnoreCase("bestenliste"))) {
                top(s);
                return;
            }
            if (args.length == 0) {
                ProxiedPlayer p = player(s);
                if (p == null) return;
                PlayerManager.Session session = plugin.players().session(p.getUniqueId());
                m().send(s, "onlinetime.own", "time", Time.hours(session == null ? 0 : session.onlineTime()));
                return;
            }
            if (!s.hasPermission("bungeesystem.onlinetime.others")) {
                m().send(s, "general.no-permission");
                return;
            }
            plugin.players().lookup(args[0]).thenAccept(opt -> {
                if (opt.isEmpty()) {
                    m().send(s, "general.player-not-found", "player", args[0]);
                    return;
                }
                UUID id = opt.get().uuid();
                PlayerManager.Session session = plugin.players().session(id);
                if (session != null) {
                    m().send(s, "onlinetime.other", "player", opt.get().name(), "time", Time.hours(session.onlineTime()));
                    return;
                }
                plugin.players().joinInfo(id).thenAccept(info ->
                        m().send(s, "onlinetime.other", "player", opt.get().name(), "time", Time.hours(info[2])));
            });
        }

        private void top(CommandSender s) {
            plugin.players().flushAll();
            plugin.db().submit(con -> {
                List<Entry> out = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement("select name, onlinetime from bs_players order by onlinetime desc limit 10")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(new Entry(rs.getString(1), rs.getLong(2)));
                    }
                }
                return out;
            }).thenAccept(list -> {
                m().send(s, "onlinetime.top-header");
                int i = 1;
                for (Entry e : list) m().send(s, "onlinetime.top-entry", "place", Integer.toString(i++), "player", e.name(), "time", Time.hours(e.time()));
            });
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) {
                List<String> out = new ArrayList<>(filter(args[0], "top"));
                if (s.hasPermission("bungeesystem.onlinetime.others")) out.addAll(players(args[0]));
                return out;
            }
            return List.of();
        }
    }

    @SuppressWarnings("unused")
    private static long total(java.sql.Connection con, UUID id) throws java.sql.SQLException {
        return Database.longValue(con, "select onlinetime from bs_players where uuid = ?", 0, id.toString());
    }
}
