package de.crafterteam.bungeesystem.punish;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.PlayerManager.Known;
import de.crafterteam.bungeesystem.util.Text;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Bans (inkl. IP-Sperre), Mutes und Kicks – serverübergreifend, mit Vorlagen-Gründen aus der config.yml. */
public final class PunishManager {

    public enum Type { BAN, MUTE }

    public record Reason(String id, String name, Type type, long duration, String permission, String item) {
        public String durationText() {
            return duration < 0 ? "Permanent" : Time.format(duration);
        }
    }

    public record Punishment(int id, Type type, UUID uuid, String name, String staff, String reason, String reasonId,
                             long created, long until, String ip, String server, boolean active, String removedBy) {
        public boolean permanent() {
            return until < 0;
        }

        public boolean expired() {
            return until > 0 && System.currentTimeMillis() > until;
        }

        public String remaining() {
            return permanent() ? "Permanent" : Time.format(Math.max(0, until - System.currentTimeMillis()));
        }

        public String untilText() {
            return permanent() ? "Nie" : Time.date(until);
        }
    }

    public record Kick(String staff, String reason, long at, String server) {
    }

    private final BungeeSystem plugin;
    private final Map<String, Reason> reasons = new LinkedHashMap<>();
    private final Map<UUID, Punishment> bans = new ConcurrentHashMap<>();
    private final Map<String, Punishment> ipBans = new ConcurrentHashMap<>();
    private final Map<UUID, Punishment> mutes = new ConcurrentHashMap<>();

    public PunishManager(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Configuration cfg() {
        return plugin.config().getSection("punish");
    }

    public void loadReasons() {
        reasons.clear();
        Configuration sec = cfg().getSection("reasons");
        for (String id : sec.getKeys()) {
            Configuration r = sec.getSection(id);
            Type type = "MUTE".equalsIgnoreCase(r.getString("type", "BAN")) ? Type.MUTE : Type.BAN;
            long d = Time.parse(r.getString("duration", "perm"));
            if (d == -2) {
                plugin.getLogger().warning("Ungültige Dauer bei punish.reasons." + id + ": " + r.getString("duration"));
                d = -1;
            }
            String perm = r.getString("permission", "");
            reasons.put(id.toLowerCase(), new Reason(id, r.getString("name", id), type, d, perm, r.getString("item", type == Type.BAN ? "BARRIER" : "PAPER")));
        }
    }

    public List<Reason> reasons(Type type) {
        return reasons.values().stream().filter(r -> r.type() == type).toList();
    }

    public List<Reason> allReasons() {
        return List.copyOf(reasons.values());
    }

    /** Vorlage per ID oder Name ("1", "hacking"). */
    public Reason reason(String key, Type type) {
        if (key == null) return null;
        Reason r = reasons.get(key.toLowerCase());
        if (r == null) {
            for (Reason x : reasons.values()) if (x.name().equalsIgnoreCase(key)) r = x;
        }
        return r != null && (type == null || r.type() == type) ? r : null;
    }

    public boolean mayUse(CommandSender s, Reason r) {
        return r.permission() == null || r.permission().isBlank() || s.hasPermission(r.permission());
    }

    // ---------------------------------------------------------------- Cache

    /** Aktive Bans & Mutes aus der DB laden (beim Start und jede Minute – mehrere Proxys bleiben synchron). */
    public CompletableFuture<Void> reload() {
        return plugin.db().submit(con -> {
            Map<UUID, Punishment> b = new ConcurrentHashMap<>();
            Map<String, Punishment> ip = new ConcurrentHashMap<>();
            Map<UUID, Punishment> m = new ConcurrentHashMap<>();
            for (Punishment p : query(con, Type.BAN, "active = 1", null)) {
                if (p.expired()) {
                    Database.exec(con, "update bs_bans set active = 0, unbanned_by = 'ABGELAUFEN' where id = ?", p.id());
                    continue;
                }
                b.put(p.uuid(), p);
                if (!p.ip().isBlank()) ip.put(p.ip(), p);
            }
            for (Punishment p : query(con, Type.MUTE, "active = 1", null)) {
                if (p.expired()) {
                    Database.exec(con, "update bs_mutes set active = 0, unmuted_by = 'ABGELAUFEN' where id = ?", p.id());
                    continue;
                }
                m.put(p.uuid(), p);
            }
            bans.clear();
            bans.putAll(b);
            ipBans.clear();
            ipBans.putAll(ip);
            mutes.clear();
            mutes.putAll(m);
            return null;
        });
    }

    private static List<Punishment> query(Connection con, Type type, String where, String uuid) throws SQLException {
        String sql = type == Type.BAN
                ? "select id, uuid, player_name, staff_name, reason, reason_id, banned_at, until_time, ip, server, active, unbanned_by from bs_bans"
                : "select id, uuid, player_name, staff_name, reason, reason_id, muted_at, until_time, '' as ip, server, active, unmuted_by from bs_mutes";
        sql += " where " + where + (uuid != null ? " and uuid = ?" : "") + " order by id desc" + (uuid != null ? " limit 30" : "");
        List<Punishment> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            if (uuid != null) ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Punishment(rs.getInt(1), type, UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getString(9) == null ? "" : rs.getString(9),
                            rs.getString(10), rs.getBoolean(11), rs.getString(12)));
                }
            }
        }
        return out;
    }

    public Punishment ban(UUID uuid) {
        Punishment p = bans.get(uuid);
        if (p != null && p.expired()) {
            bans.remove(uuid);
            ipBans.values().removeIf(x -> x.id() == p.id());
            plugin.db().run(con -> Database.exec(con, "update bs_bans set active = 0, unbanned_by = 'ABGELAUFEN' where id = ?", p.id()));
            return null;
        }
        return p;
    }

    /** Ban für Login: per UUID oder (wenn aktiviert) über die IP eines gebannten Accounts. */
    public Punishment banFor(UUID uuid, String ip) {
        Punishment p = ban(uuid);
        if (p != null) return p;
        if (!cfg().getBoolean("ip-ban", true) || ip == null || ip.isBlank()) return null;
        Punishment byIp = ipBans.get(ip);
        if (byIp == null) return null;
        return ban(byIp.uuid());
    }

    public Punishment mute(UUID uuid) {
        Punishment p = mutes.get(uuid);
        if (p != null && p.expired()) {
            mutes.remove(uuid);
            plugin.db().run(con -> Database.exec(con, "update bs_mutes set active = 0, unmuted_by = 'ABGELAUFEN' where id = ?", p.id()));
            return null;
        }
        return p;
    }

    public CompletableFuture<List<Punishment>> history(UUID uuid, Type type) {
        return plugin.db().submit(con -> query(con, type, "1 = 1", uuid.toString()));
    }

    public CompletableFuture<List<Kick>> kicks(UUID uuid) {
        return plugin.db().submit(con -> {
            List<Kick> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("select staff_name, reason, kicked_at, server from bs_kicks where uuid = ? order by id desc limit 15")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Kick(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)));
                }
            }
            return out;
        });
    }

    // ---------------------------------------------------------------- Aktionen

    /**
     * Strafe verhängen. reason = Vorlage (oder null bei eigenem Grund), custom/duration für eigene Gründe.
     * Mit Eskalation (punish.escalation) verdoppelt sich die Dauer pro früherer Strafe mit derselben Vorlage.
     */
    public CompletableFuture<Punishment> punish(CommandSender staff, Known target, Type type, Reason reason, String custom, long duration) {
        String staffName = staff instanceof ProxiedPlayer p ? p.getName() : "Konsole";
        String reasonText = reason != null ? reason.name() : custom;
        String reasonId = reason != null ? reason.id() : "";
        ProxiedPlayer online = ProxyServer.getInstance().getPlayer(target.uuid());
        String server = online != null && online.getServer() != null ? online.getServer().getInfo().getName() : "";
        String ip = online != null ? de.crafterteam.bungeesystem.core.PlayerManager.ip(online) : target.ip();
        boolean ipBan = cfg().getBoolean("ip-ban", true);
        return plugin.db().submit(con -> {
            long dur = reason != null ? reason.duration() : duration;
            if (reason != null && dur > 0 && cfg().getBoolean("escalation.enabled", true)) {
                String table = type == Type.BAN ? "bs_bans" : "bs_mutes";
                long previous = Database.longValue(con, "select count(*) from " + table + " where uuid = ? and reason_id = ?", 0,
                        target.uuid().toString(), reasonId);
                int permAfter = cfg().getInt("escalation.permanent-after", 4);
                if (permAfter > 0 && previous >= permAfter && type == Type.BAN) dur = -1;
                else if (previous > 0) dur = Math.min(dur * (1L << Math.min(previous, 10)), 365L * 86_400_000L * 5);
            }
            long now = System.currentTimeMillis();
            long until = dur < 0 ? -1 : now + dur;
            int id;
            if (type == Type.BAN) {
                Database.exec(con, "update bs_bans set active = 0, unbanned_by = ?, unbanned_at = ? where uuid = ? and active = 1", "NEUER BAN", now, target.uuid().toString());
                try (PreparedStatement ps = con.prepareStatement("insert into bs_bans (uuid, player_name, staff_name, reason, banned_at, until_time, active, ip, reason_id, server) "
                        + "values (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, target.uuid().toString());
                    ps.setString(2, target.name());
                    ps.setString(3, staffName);
                    ps.setString(4, reasonText);
                    ps.setLong(5, now);
                    ps.setLong(6, until);
                    ps.setString(7, ipBan && ip != null ? ip : "");
                    ps.setString(8, reasonId);
                    ps.setString(9, server);
                    ps.executeUpdate();
                    id = generated(ps);
                }
            } else {
                Database.exec(con, "update bs_mutes set active = 0, unmuted_by = ? where uuid = ? and active = 1", "NEUER MUTE", target.uuid().toString());
                try (PreparedStatement ps = con.prepareStatement("insert into bs_mutes (uuid, player_name, staff_name, reason, muted_at, until_time, active, reason_id, server) "
                        + "values (?, ?, ?, ?, ?, ?, 1, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, target.uuid().toString());
                    ps.setString(2, target.name());
                    ps.setString(3, staffName);
                    ps.setString(4, reasonText);
                    ps.setLong(5, now);
                    ps.setLong(6, until);
                    ps.setString(7, reasonId);
                    ps.setString(8, server);
                    ps.executeUpdate();
                    id = generated(ps);
                }
            }
            Punishment p = new Punishment(id, type, target.uuid(), target.name(), staffName, reasonText, reasonId, now, until,
                    type == Type.BAN && ipBan && ip != null ? ip : "", server, true, null);
            apply(p);
            return p;
        });
    }

    private static int generated(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** Nach dem Speichern: Cache, Kick, Nachrichten. */
    private void apply(Punishment p) {
        String[] ph = {"player", p.name(), "staff", p.staff(), "reason", p.reason(), "duration", p.permanent() ? "Permanent" : Time.format(p.until() - p.created()),
                "until", p.untilText(), "id", "#" + p.id()};
        if (p.type() == Type.BAN) {
            bans.put(p.uuid(), p);
            if (!p.ip().isBlank()) ipBans.put(p.ip(), p);
            ProxiedPlayer online = ProxyServer.getInstance().getPlayer(p.uuid());
            if (online != null) online.disconnect(TextComponent.fromLegacyText(screen(p)));
            // weitere Accounts mit derselben IP ebenfalls trennen
            if (!p.ip().isBlank()) {
                for (ProxiedPlayer other : ProxyServer.getInstance().getPlayers()) {
                    if (!other.getUniqueId().equals(p.uuid()) && p.ip().equals(de.crafterteam.bungeesystem.core.PlayerManager.ip(other))) {
                        other.disconnect(TextComponent.fromLegacyText(screen(p)));
                    }
                }
            }
            plugin.notifier().notify("ban", p.permanent() ? "ban.notify" : "ban.notify-temp", ph);
            if (cfg().getBoolean("broadcast", false)) broadcast(plugin.messages().raw("ban.broadcast", ph));
        } else {
            mutes.put(p.uuid(), p);
            ProxiedPlayer online = ProxyServer.getInstance().getPlayer(p.uuid());
            if (online != null) plugin.messages().send(online, "mute.muted", ph);
            plugin.notifier().notify("mute", "mute.notify", ph);
            if (cfg().getBoolean("broadcast", false)) broadcast(plugin.messages().raw("mute.broadcast", ph));
        }
    }

    private static void broadcast(String text) {
        for (ProxiedPlayer pl : ProxyServer.getInstance().getPlayers()) de.crafterteam.bungeesystem.core.Messages.sendRaw(pl, text);
    }

    public String screen(Punishment p) {
        return plugin.messages().legacy(p.permanent() ? "ban.screen" : "ban.screen-temp",
                "staff", p.staff(), "reason", p.reason(), "until", p.untilText(), "remaining", p.remaining(), "id", "#" + p.id(),
                "date", Time.date(p.created()));
    }

    /** Aktive Strafe auf ein neues Ende setzen (Exemption/Verkürzung). */
    public CompletableFuture<Punishment> shorten(UUID uuid, Type type, long newUntil) {
        Punishment p = type == Type.BAN ? ban(uuid) : mute(uuid);
        if (p == null) return CompletableFuture.completedFuture(null);
        Punishment updated = new Punishment(p.id(), p.type(), p.uuid(), p.name(), p.staff(), p.reason(), p.reasonId(), p.created(),
                newUntil, p.ip(), p.server(), true, null);
        if (type == Type.BAN) {
            bans.put(uuid, updated);
            if (!updated.ip().isBlank()) ipBans.put(updated.ip(), updated);
        } else {
            mutes.put(uuid, updated);
        }
        String table = type == Type.BAN ? "bs_bans" : "bs_mutes";
        return plugin.db().submit(con -> {
            Database.exec(con, "update " + table + " set until_time = ? where id = ?", newUntil, p.id());
            return updated;
        });
    }

    /** Erledigte Einträge (nicht aktive Bans/Mutes, Kicks, Warns) eines Spielers löschen. */
    public CompletableFuture<Integer> clearHistory(UUID uuid) {
        return plugin.db().submit(con -> Database.exec(con, "delete from bs_bans where uuid = ? and active = 0", uuid.toString())
                + Database.exec(con, "delete from bs_mutes where uuid = ? and active = 0", uuid.toString())
                + Database.exec(con, "delete from bs_kicks where uuid = ?", uuid.toString())
                + Database.exec(con, "delete from bs_warns where uuid = ?", uuid.toString()));
    }

    public CompletableFuture<Boolean> unban(UUID uuid, String staff) {
        Punishment old = bans.remove(uuid);
        ipBans.values().removeIf(x -> x.uuid().equals(uuid));
        return plugin.db().submit(con -> Database.exec(con, "update bs_bans set active = 0, unbanned_by = ?, unbanned_at = ? where uuid = ? and active = 1",
                staff, System.currentTimeMillis(), uuid.toString()) > 0 || old != null);
    }

    public CompletableFuture<Boolean> unmute(UUID uuid, String staff) {
        Punishment old = mutes.remove(uuid);
        return plugin.db().submit(con -> Database.exec(con, "update bs_mutes set active = 0, unmuted_by = ? where uuid = ? and active = 1",
                staff, uuid.toString()) > 0 || old != null);
    }

    public void kick(CommandSender staff, ProxiedPlayer target, String reason) {
        String staffName = staff instanceof ProxiedPlayer p ? p.getName() : "Konsole";
        String server = target.getServer() != null ? target.getServer().getInfo().getName() : "";
        target.disconnect(TextComponent.fromLegacyText(plugin.messages().legacy("kick.screen", "staff", staffName, "reason", reason)));
        String uuid = target.getUniqueId().toString(), name = target.getName();
        plugin.db().run(con -> Database.exec(con, "insert into bs_kicks (uuid, player_name, staff_name, reason, kicked_at, server) values (?, ?, ?, ?, ?, ?)",
                uuid, name, staffName, reason, System.currentTimeMillis(), server));
        plugin.notifier().notify("kick", "kick.notify", "player", name, "staff", staffName, "reason", reason, "server", server);
    }

    /** Für Chat-Hinweis bei Mute. */
    public String muteMessage(Punishment p) {
        return plugin.messages().raw("mute.blocked", "reason", p.reason(), "remaining", p.remaining(), "until", p.untilText(), "staff", p.staff());
    }

    public static String strip(String s) {
        return Text.strip(s);
    }
}
