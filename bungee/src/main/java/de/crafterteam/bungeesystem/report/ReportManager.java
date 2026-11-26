package de.crafterteam.bungeesystem.report;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Report-System: /report &lt;Spieler&gt; &lt;Grund&gt;, Team-Benachrichtigung, GUI mit allen offenen Reports. */
public final class ReportManager {

    public record Report(int id, UUID reporter, String reporterName, UUID target, String targetName, String reason, String server,
                         long created, String status, String handler) {
    }

    private final BungeeSystem plugin;
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    public ReportManager(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Configuration cfg() {
        return plugin.config().getSection("report");
    }

    public List<String> reasons() {
        return cfg().getStringList("reasons");
    }

    private Messages m() {
        return plugin.messages();
    }

    public void report(ProxiedPlayer reporter, String targetName, String reasonInput) {
        if (reporter.getName().equalsIgnoreCase(targetName)) {
            m().send(reporter, "report.self");
            return;
        }
        long cd = cfg().getLong("cooldown-seconds", 60) * 1000L;
        long last = cooldown.getOrDefault(reporter.getUniqueId(), 0L);
        if (!reporter.hasPermission("bungeesystem.report.bypass") && last + cd > System.currentTimeMillis()) {
            m().send(reporter, "report.cooldown", "seconds", Long.toString((last + cd - System.currentTimeMillis()) / 1000 + 1));
            return;
        }
        String reason = null;
        for (String r : reasons()) if (r.equalsIgnoreCase(reasonInput)) reason = r;
        if (reason == null) {
            if (!cfg().getBoolean("custom-reasons", false) || reasonInput.isBlank()) {
                m().send(reporter, "report.invalid-reason", "reasons", String.join(", ", reasons()));
                return;
            }
            reason = reasonInput.length() > 100 ? reasonInput.substring(0, 100) : reasonInput;
        }
        ProxiedPlayer target = ProxyServer.getInstance().getPlayer(targetName);
        if (target == null && cfg().getBoolean("only-online", true)) {
            m().send(reporter, "general.player-offline", "player", targetName);
            return;
        }
        if (target != null && target.hasPermission("bungeesystem.report.protected")) {
            m().send(reporter, "report.protected", "player", target.getName());
            return;
        }
        String finalReason = reason;
        String server = target != null && target.getServer() != null ? target.getServer().getInfo().getName()
                : reporter.getServer() != null ? reporter.getServer().getInfo().getName() : "";
        cooldown.put(reporter.getUniqueId(), System.currentTimeMillis());
        plugin.players().lookup(targetName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                m().send(reporter, "general.player-not-found", "player", targetName);
                return;
            }
            var t = opt.get();
            plugin.db().submit(con -> {
                if (Database.exists(con, "select 1 from bs_reports where reporter = ? and target = ? and status <> 'CLOSED'",
                        reporter.getUniqueId().toString(), t.uuid().toString())) {
                    return -1;
                }
                try (PreparedStatement ps = con.prepareStatement("insert into bs_reports (reporter, reporter_name, target, target_name, reason, server, created) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, reporter.getUniqueId().toString());
                    ps.setString(2, reporter.getName());
                    ps.setString(3, t.uuid().toString());
                    ps.setString(4, t.name());
                    ps.setString(5, finalReason);
                    ps.setString(6, server);
                    ps.setLong(7, System.currentTimeMillis());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }).thenAccept(id -> {
                if (id < 0) {
                    m().send(reporter, "report.already", "player", t.name());
                    return;
                }
                m().send(reporter, "report.sent", "player", t.name(), "reason", finalReason);
                plugin.notifier().notify("report", "report.notify", "id", Integer.toString(id), "player", t.name(), "reporter", reporter.getName(),
                        "reason", finalReason, "server", server, "cmd", plugin.commandName("report"));
            });
        });
    }

    public CompletableFuture<List<Report>> open() {
        return plugin.db().submit(con -> {
            List<Report> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("select id, reporter, reporter_name, target, target_name, reason, server, created, status, handler "
                    + "from bs_reports where status <> 'CLOSED' order by id limit 45")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Report(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), UUID.fromString(rs.getString(4)),
                                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9), rs.getString(10)));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<Report> get(int id) {
        return plugin.db().submit(con -> {
            try (PreparedStatement ps = con.prepareStatement("select id, reporter, reporter_name, target, target_name, reason, server, created, status, handler "
                    + "from bs_reports where id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new Report(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), UUID.fromString(rs.getString(4)),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9), rs.getString(10));
                }
            }
        });
    }

    /** Report übernehmen: Status "in Bearbeitung", Teammitglied springt auf den Server des Gemeldeten. */
    public void accept(ProxiedPlayer staff, int id) {
        get(id).thenAccept(r -> {
            if (r == null || r.status().equals("CLOSED")) {
                m().send(staff, "report.not-found", "id", Integer.toString(id));
                return;
            }
            if (r.status().equals("PROGRESS") && !r.handler().equalsIgnoreCase(staff.getName())) {
                m().send(staff, "report.taken", "id", Integer.toString(id), "handler", r.handler());
                return;
            }
            plugin.db().run(con -> Database.exec(con, "update bs_reports set status = 'PROGRESS', handler = ?, handled_at = ? where id = ?",
                    staff.getName(), System.currentTimeMillis(), id));
            m().send(staff, "report.accepted", "id", Integer.toString(id), "player", r.targetName());
            plugin.notifier().notify("report", "report.notify-accepted", "id", Integer.toString(id), "staff", staff.getName(), "player", r.targetName());
            ProxiedPlayer reporter = ProxyServer.getInstance().getPlayer(r.reporter());
            if (reporter != null) m().send(reporter, "report.reporter-accepted", "player", r.targetName());
            ProxiedPlayer target = ProxyServer.getInstance().getPlayer(r.target());
            if (target != null && target.getServer() != null) {
                ServerInfo s = target.getServer().getInfo();
                if (staff.getServer() == null || !staff.getServer().getInfo().equals(s)) staff.connect(s);
            } else {
                m().send(staff, "report.target-offline", "player", r.targetName());
            }
        });
    }

    public void close(ProxiedPlayer staff, int id) {
        get(id).thenAccept(r -> {
            if (r == null || r.status().equals("CLOSED")) {
                m().send(staff, "report.not-found", "id", Integer.toString(id));
                return;
            }
            plugin.db().run(con -> Database.exec(con, "update bs_reports set status = 'CLOSED', handler = ?, handled_at = ? where id = ?",
                    staff.getName(), System.currentTimeMillis(), id));
            m().send(staff, "report.closed", "id", Integer.toString(id));
            ProxiedPlayer reporter = ProxyServer.getInstance().getPlayer(r.reporter());
            if (reporter != null) m().send(reporter, "report.reporter-closed", "player", r.targetName());
        });
    }

    /** /reports → GUI (Addon) oder klickbare Liste im Chat. */
    public void list(ProxiedPlayer staff) {
        open().thenAccept(list -> {
            if (plugin.bridge().hasAddon(staff)) {
                plugin.guis().openReports(staff, list);
                return;
            }
            m().send(staff, "report.list-header", "count", Integer.toString(list.size()));
            if (list.isEmpty()) m().send(staff, "report.list-empty");
            for (Report r : list) {
                m().send(staff, "report.list-entry", "id", Integer.toString(r.id()), "player", r.targetName(), "reporter", r.reporterName(),
                        "reason", r.reason(), "status", r.status().equals("OPEN") ? m().raw("report.status-open") : m().raw("report.status-progress", "handler", r.handler()),
                        "cmd", plugin.commandName("report"));
            }
        });
    }

    /** Erinnerung beim Join für Teammitglieder. */
    public void remind(ProxiedPlayer staff) {
        if (!plugin.notifier().receives(staff, "report")) return;
        plugin.db().submit(con -> Database.longValue(con, "select count(*) from bs_reports where status = 'OPEN'", 0)).thenAccept(n -> {
            if (n > 0) m().send(staff, "report.reminder", "count", Long.toString(n), "cmd", plugin.commandName("reports"));
        });
    }

    // ================================================================ Befehle

    public static void register(BungeeSystem plugin) {
        plugin.command("report", n -> new ReportCmd(plugin, n));
        plugin.command("reports", n -> new ReportsCmd(plugin, n));
    }

    static final class ReportCmd extends Cmd {
        ReportCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            ReportManager r = plugin.reports();
            if (args.length >= 2 && p.hasPermission("bungeesystem.report.manage") && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("close"))) {
                int id;
                try {
                    id = Integer.parseInt(args[1].replace("#", ""));
                } catch (NumberFormatException e) {
                    m().send(s, "report.not-found", "id", args[1]);
                    return;
                }
                if (args[0].equalsIgnoreCase("accept")) r.accept(p, id);
                else r.close(p, id);
                return;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("list") && p.hasPermission("bungeesystem.report.manage")) {
                r.list(p);
                return;
            }
            if (args.length < 2) {
                m().send(s, "report.usage", "cmd", getName(), "reasons", String.join(", ", r.reasons()));
                if (args.length == 1) {
                    for (String reason : r.reasons()) {
                        m().send(s, "report.reason-button", "reason", reason, "command", "/" + getName() + " " + args[0] + " " + reason);
                    }
                }
                return;
            }
            r.report(p, args[0], join(args, 1));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) {
                List<String> out = new ArrayList<>(players(args[0]));
                if (s.hasPermission("bungeesystem.report.manage")) out.addAll(filter(args[0], "accept", "close", "list"));
                return out;
            }
            if (args.length == 2) return filter(args[1], plugin.reports().reasons());
            return List.of();
        }
    }

    static final class ReportsCmd extends Cmd {
        ReportsCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.report.manage");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p != null) plugin.reports().list(p);
        }
    }
}
