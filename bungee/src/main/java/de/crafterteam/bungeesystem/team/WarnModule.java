package de.crafterteam.bungeesystem.team;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.punish.PunishCommands;
import de.crafterteam.bungeesystem.punish.PunishManager;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** /warn add|remove|list – ab warn.max aktiven Verwarnungen automatisch Ban. /exemption verkürzt Bans & Mutes. */
public final class WarnModule {

    private WarnModule() {
    }

    record Warn(int id, String staff, String reason, long created) {
    }

    public static void registerWarn(BungeeSystem plugin) {
        plugin.command("warn", n -> new WarnCmd(plugin, n));
    }

    public static void registerExemption(BungeeSystem plugin) {
        plugin.command("exemption", n -> new ExemptionCmd(plugin, n));
    }

    static final class WarnCmd extends Cmd {
        WarnCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.warn");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length < 2) {
                m().send(s, "warn.usage", "cmd", getName());
                return;
            }
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "add" -> {
                    if (args.length < 3) {
                        m().send(s, "warn.usage", "cmd", getName());
                        return;
                    }
                    add(s, args[1], join(args, 2));
                }
                case "remove" -> PunishCommands.resolve(plugin, s, args[1], t -> plugin.db().submit(con -> {
                    long id = Database.longValue(con, "select id from bs_warns where uuid = ? and active = 1 order by id desc limit 1", -1, t.uuid().toString());
                    if (id < 0) return false;
                    Database.exec(con, "update bs_warns set active = 0 where id = ?", id);
                    return true;
                }).thenAccept(ok -> m().send(s, ok ? "warn.removed" : "warn.none", "player", t.name())));
                case "list" -> PunishCommands.resolve(plugin, s, args[1], t -> plugin.db().submit(con -> {
                    List<Warn> out = new ArrayList<>();
                    try (PreparedStatement ps = con.prepareStatement("select id, staff_name, reason, created from bs_warns where uuid = ? and active = 1 order by id")) {
                        ps.setString(1, t.uuid().toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) out.add(new Warn(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                        }
                    }
                    return out;
                }).thenAccept(list -> {
                    m().send(s, "warn.list-header", "player", t.name(), "count", Integer.toString(list.size()),
                            "max", Integer.toString(plugin.config().getInt("warn.max", 3)));
                    for (Warn w : list) m().send(s, "warn.list-entry", "id", "#" + w.id(), "reason", w.reason(), "staff", w.staff(), "date", Time.date(w.created()));
                }));
                default -> add(s, args[0], join(args, 1)); // /warn Name Grund
            }
        }

        private void add(CommandSender s, String target, String reason) {
            int max = plugin.config().getInt("warn.max", 3);
            PunishCommands.resolve(plugin, s, target, t -> {
                ProxiedPlayer online = ProxyServer.getInstance().getPlayer(t.uuid());
                if (online != null && online.hasPermission("bungeesystem.punish.protected") && !s.hasPermission("bungeesystem.punish.protected.bypass")) {
                    m().send(s, "punish.protected", "player", t.name());
                    return;
                }
                plugin.db().submit(con -> {
                    Database.exec(con, "insert into bs_warns (uuid, player_name, staff_name, reason, created) values (?, ?, ?, ?, ?)",
                            t.uuid().toString(), t.name(), name(s), reason, System.currentTimeMillis());
                    return Database.longValue(con, "select count(*) from bs_warns where uuid = ? and active = 1", 0, t.uuid().toString());
                }).thenAccept(count -> {
                    m().send(s, "warn.success", "player", t.name(), "reason", reason, "count", Long.toString(count), "max", Integer.toString(max));
                    if (online != null) m().send(online, "warn.target", "reason", reason, "staff", name(s), "count", Long.toString(count), "max", Integer.toString(max));
                    plugin.notifier().notify("ban", "warn.notify", "player", t.name(), "staff", name(s), "reason", reason,
                            "count", Long.toString(count), "max", Integer.toString(max));
                    if (max > 0 && count >= max) autoBan(s, t, count);
                });
            });
        }

        private void autoBan(CommandSender s, de.crafterteam.bungeesystem.core.PlayerManager.Known t, long count) {
            PunishManager pm = plugin.punish();
            PunishManager.Reason tpl = pm.reason(plugin.config().getString("warn.ban-reason", ""), PunishManager.Type.BAN);
            long duration = Time.parse(plugin.config().getString("warn.ban-duration", "3d"));
            String text = plugin.messages().raw("warn.ban-reason", "count", Long.toString(count));
            pm.punish(ProxyServer.getInstance().getConsole(), t, PunishManager.Type.BAN, tpl, tpl == null ? text : null, duration == -2 ? 3 * 86_400_000L : duration)
                    .thenAccept(p -> {
                        plugin.db().run(con -> Database.exec(con, "update bs_warns set active = 0 where uuid = ?", t.uuid().toString()));
                        m().send(s, "warn.auto-ban", "player", t.name(), "count", Long.toString(count));
                    });
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) {
                List<String> out = new ArrayList<>(filter(args[0], "add", "remove", "list"));
                out.addAll(players(args[0]));
                return out;
            }
            if (args.length == 2) return players(args[1]);
            return List.of();
        }
    }

    /** /exemption <Spieler> <ban|mute> <50%|2d> – Restdauer verkürzen (Prozent oder neue Restzeit). */
    static final class ExemptionCmd extends Cmd {
        ExemptionCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.exemption");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length < 3) {
                m().send(s, "exemption.usage", "cmd", getName());
                return;
            }
            PunishManager.Type type = args[1].equalsIgnoreCase("mute") ? PunishManager.Type.MUTE : PunishManager.Type.BAN;
            String amount = args[2];
            PunishCommands.resolve(plugin, s, args[0], t -> {
                PunishManager.Punishment p = type == PunishManager.Type.BAN ? plugin.punish().ban(t.uuid()) : plugin.punish().mute(t.uuid());
                if (p == null) {
                    m().send(s, type == PunishManager.Type.BAN ? "unban.not" : "unmute.not", "player", t.name());
                    return;
                }
                long now = System.currentTimeMillis();
                long remaining = p.permanent() ? -1 : p.until() - now;
                long newRemaining;
                if (amount.endsWith("%")) {
                    int pct;
                    try {
                        pct = Math.max(1, Math.min(100, Integer.parseInt(amount.substring(0, amount.length() - 1))));
                    } catch (NumberFormatException e) {
                        m().send(s, "exemption.usage", "cmd", getName());
                        return;
                    }
                    if (remaining < 0) {
                        m().send(s, "exemption.permanent");
                        return;
                    }
                    newRemaining = remaining * (100 - pct) / 100;
                } else {
                    newRemaining = Time.parse(amount);
                    if (newRemaining <= 0) {
                        m().send(s, "exemption.usage", "cmd", getName());
                        return;
                    }
                    if (remaining >= 0 && newRemaining >= remaining) {
                        m().send(s, "exemption.not-shorter");
                        return;
                    }
                }
                if (newRemaining <= 0) {
                    (type == PunishManager.Type.BAN ? plugin.punish().unban(t.uuid(), name(s)) : plugin.punish().unmute(t.uuid(), name(s)))
                            .thenAccept(ok -> m().send(s, "exemption.removed", "player", t.name()));
                    return;
                }
                plugin.punish().shorten(t.uuid(), type, now + newRemaining).thenAccept(u -> {
                    m().send(s, "exemption.success", "player", t.name(), "remaining", u.remaining());
                    plugin.notifier().notify(type == PunishManager.Type.BAN ? "ban" : "mute", "exemption.notify", "player", t.name(),
                            "staff", name(s), "remaining", u.remaining());
                });
            });
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return players(args[0]);
            if (args.length == 2) return filter(args[1], "ban", "mute");
            if (args.length == 3) return filter(args[2], "25%", "50%", "75%", "1d", "12h");
            return List.of();
        }
    }
}
