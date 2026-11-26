package de.crafterteam.bungeesystem.team;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /support – Spieler fragt Hilfe an, ein Teammitglied nimmt an und beide schreiben in einem privaten Support-Raum.
 * /bugreport – Bugs melden; angenommene Bugs bringen Coins.
 */
public final class SupportModule implements Listener {

    record Request(String name, String topic, long created) {
    }

    private final BungeeSystem plugin;
    private final Map<UUID, Request> requests = new LinkedHashMap<>();
    /** Spieler ↔ Teammitglied (beide Richtungen eingetragen). */
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();

    public SupportModule(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    public void registerSupport() {
        plugin.getProxy().getPluginManager().registerListener(plugin, this);
        plugin.command("support", n -> new SupportCmd(plugin, n));
    }

    private Messages m() {
        return plugin.messages();
    }

    private void close(UUID a, boolean notify) {
        UUID b = sessions.remove(a);
        if (b == null) return;
        sessions.remove(b);
        if (!notify) return;
        for (UUID u : new UUID[]{a, b}) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(u);
            if (p != null) m().send(p, "support.closed");
        }
    }

    @EventHandler
    public void onQuit(PlayerDisconnectEvent e) {
        synchronized (requests) {
            requests.remove(e.getPlayer().getUniqueId());
        }
        close(e.getPlayer().getUniqueId(), true);
    }

    final class SupportCmd extends Cmd {
        SupportCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            boolean staff = p.hasPermission("bungeesystem.support");
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            if (staff && sub.equals("accept") && args.length > 1) {
                ProxiedPlayer t = ProxyServer.getInstance().getPlayer(args[1]);
                Request r;
                synchronized (requests) {
                    r = t == null ? null : requests.remove(t.getUniqueId());
                }
                if (t == null || r == null) {
                    m().send(p, "support.no-request", "player", args[1]);
                    return;
                }
                close(p.getUniqueId(), true);
                sessions.put(p.getUniqueId(), t.getUniqueId());
                sessions.put(t.getUniqueId(), p.getUniqueId());
                m().send(p, "support.accepted-staff", "player", t.getName(), "topic", r.topic(), "cmd", getName());
                m().send(t, "support.accepted-player", "staff", p.getName(), "cmd", getName());
                plugin.notifier().notify("report", "support.notify-accepted", "player", t.getName(), "staff", p.getName());
                return;
            }
            if (staff && sub.equals("list")) {
                List<Map.Entry<UUID, Request>> list;
                synchronized (requests) {
                    list = new ArrayList<>(requests.entrySet());
                }
                m().send(p, "support.list-header", "count", Integer.toString(list.size()));
                for (var e : list) m().send(p, "support.list-entry", "player", e.getValue().name(), "topic", e.getValue().topic(),
                        "date", Time.date(e.getValue().created()), "cmd", getName());
                return;
            }
            if (sub.equals("close") || sub.equals("cancel")) {
                boolean had;
                synchronized (requests) {
                    had = requests.remove(p.getUniqueId()) != null;
                }
                if (sessions.containsKey(p.getUniqueId())) close(p.getUniqueId(), true);
                else m().send(p, had ? "support.cancelled" : "support.nothing");
                return;
            }
            // im Support-Raum: Nachricht an den Partner
            UUID partner = sessions.get(p.getUniqueId());
            if (partner != null) {
                if (args.length == 0) {
                    m().send(p, "support.in-session", "cmd", getName());
                    return;
                }
                ProxiedPlayer other = ProxyServer.getInstance().getPlayer(partner);
                String text = m().raw("support.chat", "player", p.getName(), "message", join(args, 0));
                Messages.sendRaw(p, text);
                if (other != null) Messages.sendRaw(other, text);
                return;
            }
            if (staff && args.length == 0) {
                m().send(p, "support.help-staff", "cmd", getName());
                return;
            }
            synchronized (requests) {
                if (requests.containsKey(p.getUniqueId())) {
                    m().send(p, "support.already");
                    return;
                }
                requests.put(p.getUniqueId(), new Request(p.getName(), args.length == 0 ? "-" : join(args, 0), System.currentTimeMillis()));
            }
            m().send(p, "support.requested");
            long staffOnline = ProxyServer.getInstance().getPlayers().stream().filter(x -> x.hasPermission("bungeesystem.support")).count();
            if (staffOnline == 0) m().send(p, "support.no-staff");
            for (ProxiedPlayer x : ProxyServer.getInstance().getPlayers()) {
                if (x.hasPermission("bungeesystem.support") && plugin.notifier().receives(x, "report")) {
                    m().send(x, "support.notify", "player", p.getName(), "topic", args.length == 0 ? "-" : join(args, 0), "cmd", getName());
                }
            }
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (!s.hasPermission("bungeesystem.support")) return args.length == 1 ? filter(args[0], "close") : List.of();
            if (args.length == 1) return filter(args[0], "accept", "list", "close");
            if (args.length == 2) {
                synchronized (requests) {
                    return filter(args[1], requests.values().stream().map(Request::name).toList());
                }
            }
            return List.of();
        }
    }

    // ================================================================ BugReport

    record Bug(int id, String name, UUID uuid, String server, String message, long created, String status) {
    }

    public static void registerBugReport(BungeeSystem plugin) {
        plugin.command("bugreport", n -> new BugCmd(plugin, n));
    }

    static final class BugCmd extends Cmd {
        BugCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            boolean staff = s.hasPermission("bungeesystem.bugreport.manage");
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            if (staff && sub.equals("list")) {
                plugin.db().submit(con -> {
                    List<Bug> out = new ArrayList<>();
                    try (PreparedStatement ps = con.prepareStatement("select id, player_name, uuid, server, message, created, status from bs_bugreports where status = 'OPEN' order by id limit 30");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(bug(rs));
                    }
                    return out;
                }).thenAccept(list -> {
                    m().send(s, "bugreport.list-header", "count", Integer.toString(list.size()));
                    for (Bug b : list) m().send(s, "bugreport.list-entry", "id", Integer.toString(b.id()), "player", b.name(), "server", b.server(),
                            "message", b.message().length() > 40 ? b.message().substring(0, 40) + "…" : b.message(), "cmd", getName());
                });
                return;
            }
            if (staff && (sub.equals("info") || sub.equals("accept") || sub.equals("deny")) && args.length > 1) {
                int id;
                try {
                    id = Integer.parseInt(args[1].replace("#", ""));
                } catch (NumberFormatException e) {
                    m().send(s, "bugreport.not-found", "id", args[1]);
                    return;
                }
                plugin.db().submit(con -> {
                    try (PreparedStatement ps = con.prepareStatement("select id, player_name, uuid, server, message, created, status from bs_bugreports where id = ?")) {
                        ps.setInt(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? bug(rs) : null;
                        }
                    }
                }).thenAccept(b -> {
                    if (b == null) {
                        m().send(s, "bugreport.not-found", "id", Integer.toString(id));
                        return;
                    }
                    if (sub.equals("info")) {
                        m().send(s, "bugreport.info", "id", Integer.toString(b.id()), "player", b.name(), "server", b.server(), "message", b.message(),
                                "date", Time.date(b.created()), "status", b.status(), "cmd", getName());
                        return;
                    }
                    if (!b.status().equals("OPEN")) {
                        m().send(s, "bugreport.already-done", "id", Integer.toString(id));
                        return;
                    }
                    boolean accept = sub.equals("accept");
                    plugin.db().run(con -> Database.exec(con, "update bs_bugreports set status = ?, handler = ? where id = ?",
                            accept ? "ACCEPTED" : "DENIED", name(s), id));
                    long reward = plugin.config().getLong("bugreport.reward-coins", 250);
                    ProxiedPlayer reporter = ProxyServer.getInstance().getPlayer(b.uuid());
                    if (accept && reward > 0) plugin.bridge().addCoins(b.uuid(), reward);
                    m().send(s, accept ? "bugreport.accepted" : "bugreport.denied", "id", Integer.toString(id), "player", b.name(), "coins", Long.toString(reward));
                    if (reporter != null) m().send(reporter, accept ? "bugreport.reporter-accepted" : "bugreport.reporter-denied",
                            "id", Integer.toString(id), "coins", Long.toString(reward));
                });
                return;
            }
            ProxiedPlayer p = player(s);
            if (p == null) return;
            if (args.length < 2) {
                m().send(s, staff ? "bugreport.usage-staff" : "bugreport.usage", "cmd", getName());
                return;
            }
            String server = args[0];
            if (ProxyServer.getInstance().getServerInfo(server) == null && p.getServer() != null) server = p.getServer().getInfo().getName() + " (" + args[0] + ")";
            String message = join(args, 1);
            if (message.length() > 500) message = message.substring(0, 500);
            String srv = server, msg = message;
            plugin.db().submit(con -> {
                if (Database.longValue(con, "select count(*) from bs_bugreports where uuid = ? and status = 'OPEN'", 0, p.getUniqueId().toString())
                        >= plugin.config().getInt("bugreport.max-open", 3)) return -1;
                try (PreparedStatement ps = con.prepareStatement("insert into bs_bugreports (uuid, player_name, server, message, created) values (?, ?, ?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, p.getUniqueId().toString());
                    ps.setString(2, p.getName());
                    ps.setString(3, srv);
                    ps.setString(4, msg);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }).thenAccept(id -> {
                if (id < 0) {
                    m().send(p, "bugreport.too-many");
                    return;
                }
                m().send(p, "bugreport.sent", "id", Integer.toString(id));
                for (ProxiedPlayer x : ProxyServer.getInstance().getPlayers()) {
                    if (x.hasPermission("bungeesystem.bugreport.manage") && plugin.notifier().receives(x, "report")) {
                        m().send(x, "bugreport.notify", "id", Integer.toString(id), "player", p.getName(), "server", srv, "cmd", getName());
                    }
                }
            });
        }

        private static Bug bug(ResultSet rs) throws java.sql.SQLException {
            return new Bug(rs.getInt(1), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) {
                List<String> out = new ArrayList<>(filter(args[0], ProxyServer.getInstance().getServers().keySet()));
                if (s.hasPermission("bungeesystem.bugreport.manage")) out.addAll(filter(args[0], "list", "info", "accept", "deny"));
                return out;
            }
            return List.of();
        }
    }
}
