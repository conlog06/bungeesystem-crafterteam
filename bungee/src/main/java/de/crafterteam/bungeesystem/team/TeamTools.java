package de.crafterteam.bungeesystem.team;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.ArrayList;
import java.util.List;

/** TeamChat, AdminChat, TeamOnline, JoinTo, WhereIs, StaffStats. */
public final class TeamTools {

    private TeamTools() {
    }

    private static String server(ProxiedPlayer p) {
        return p.getServer() == null ? "?" : p.getServer().getInfo().getName();
    }

    // ================================================================ Team-/Admin-Chat

    public static void registerTeamChat(BungeeSystem plugin) {
        plugin.command("teamchat", n -> new StaffChat(plugin, n, "bungeesystem.teamchat", "teamchat"));
    }

    public static void registerAdminChat(BungeeSystem plugin) {
        plugin.command("adminchat", n -> new StaffChat(plugin, n, "bungeesystem.adminchat", "adminchat"));
    }

    static final class StaffChat extends Cmd {
        private final String perm, key;

        StaffChat(BungeeSystem plugin, String[] names, String perm, String key) {
            super(plugin, names, perm);
            this.perm = perm;
            this.key = key;
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length == 0) {
                m().send(s, key + ".usage", "cmd", getName());
                return;
            }
            String text = m().raw(key + ".format", "player", name(s), "message", join(args, 0),
                    "server", s instanceof ProxiedPlayer p ? server(p) : "Konsole");
            for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) if (p.hasPermission(perm)) Messages.sendRaw(p, text);
            if (!(s instanceof ProxiedPlayer)) Messages.sendRaw(s, text);
            plugin.getLogger().info("[" + key + "] " + name(s) + ": " + join(args, 0));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            return List.of();
        }
    }

    // ================================================================ TeamOnline

    public static void registerTeamOnline(BungeeSystem plugin) {
        plugin.command("teamonline", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                List<ProxiedPlayer> team = new ArrayList<>();
                for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) if (p.hasPermission("bungeesystem.team")) team.add(p);
                m().send(s, "teamonline.header", "count", Integer.toString(team.size()));
                if (team.isEmpty()) m().send(s, "teamonline.empty");
                boolean details = s.hasPermission("bungeesystem.team");
                for (ProxiedPlayer p : team) m().send(s, details ? "teamonline.entry-server" : "teamonline.entry", "player", p.getName(), "server", server(p));
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    // ================================================================ JoinTo / WhereIs

    public static void registerJoinTo(BungeeSystem plugin) {
        plugin.command("jointo", n -> new Cmd(plugin, n, "bungeesystem.jointo") {
            @Override
            protected void run(CommandSender s, String[] args) {
                ProxiedPlayer p = player(s);
                if (p == null) return;
                if (args.length == 0) {
                    m().send(s, "jointo.usage", "cmd", getName());
                    return;
                }
                ProxiedPlayer t = ProxyServer.getInstance().getPlayer(args[0]);
                if (t == null || t.getServer() == null) {
                    m().send(s, "general.player-offline", "player", args[0]);
                    return;
                }
                if (p.getServer() != null && p.getServer().getInfo().equals(t.getServer().getInfo())) {
                    m().send(s, "jointo.same", "player", t.getName());
                    return;
                }
                m().send(s, "jointo.success", "player", t.getName(), "server", server(t));
                p.connect(t.getServer().getInfo());
            }
        });
    }

    public static void registerWhereIs(BungeeSystem plugin) {
        plugin.command("whereis", n -> new Cmd(plugin, n, "bungeesystem.whereis") {
            @Override
            protected void run(CommandSender s, String[] args) {
                if (args.length == 0) {
                    m().send(s, "whereis.usage", "cmd", getName());
                    return;
                }
                ProxiedPlayer t = ProxyServer.getInstance().getPlayer(args[0]);
                if (t == null) m().send(s, "general.player-offline", "player", args[0]);
                else m().send(s, "whereis.result", "player", t.getName(), "server", server(t), "jointo", plugin.commandName("jointo"));
            }
        });
    }

    // ================================================================ StaffStats

    public static void registerStaffStats(BungeeSystem plugin) {
        plugin.command("staffstats", n -> new Cmd(plugin, n, "bungeesystem.staffstats") {
            @Override
            protected void run(CommandSender s, String[] args) {
                String who = args.length > 0 ? args[0] : s instanceof ProxiedPlayer p ? p.getName() : null;
                if (who == null) {
                    m().send(s, "staffstats.usage", "cmd", getName());
                    return;
                }
                long since = 0;
                if (args.length > 1) {
                    long d = de.crafterteam.bungeesystem.util.Time.parse(args[1]);
                    if (d > 0) since = System.currentTimeMillis() - d;
                }
                long from = since;
                plugin.db().submit(con -> new long[]{
                        Database.longValue(con, "select count(*) from bs_bans where lower(staff_name) = lower(?) and banned_at >= ?", 0, who, from),
                        Database.longValue(con, "select count(*) from bs_mutes where lower(staff_name) = lower(?) and muted_at >= ?", 0, who, from),
                        Database.longValue(con, "select count(*) from bs_kicks where lower(staff_name) = lower(?) and kicked_at >= ?", 0, who, from),
                        Database.longValue(con, "select count(*) from bs_warns where lower(staff_name) = lower(?) and created >= ?", 0, who, from),
                        Database.longValue(con, "select count(*) from bs_reports where lower(handler) = lower(?) and status = 'CLOSED' and handled_at >= ?", 0, who, from),
                        Database.longValue(con, "select count(*) from bs_bans where lower(unbanned_by) = lower(?) and unbanned_at >= ?", 0, who, from)
                }).thenAccept(v -> m().send(s, "staffstats.result", "player", who, "bans", Long.toString(v[0]), "mutes", Long.toString(v[1]),
                        "kicks", Long.toString(v[2]), "warns", Long.toString(v[3]), "reports", Long.toString(v[4]), "unbans", Long.toString(v[5]),
                        "period", args.length > 1 ? args[1] : m().raw("staffstats.all-time")));
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                if (args.length == 1) return players(args[0]);
                if (args.length == 2) return filter(args[1], "1d", "7d", "30d");
                return List.of();
            }
        });
    }
}
