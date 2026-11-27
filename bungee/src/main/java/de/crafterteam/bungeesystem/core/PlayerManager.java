package de.crafterteam.bungeesystem.core;

import de.crafterteam.bungeesystem.BungeeSystem;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.net.InetSocketAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** bs_players: Name, IP, Onlinezeit, Notify-Einstellungen & Chat-Schalter jedes Spielers. */
public final class PlayerManager {

    public record Known(UUID uuid, String name, String ip) {
    }

    /** Daten eines Online-Spielers. */
    public static final class Session {
        public final UUID uuid;
        public final String name, ip;
        public final long joined;
        /** Onlinezeit aus der DB beim Join + bereits gespeicherte Anteile. */
        public volatile long storedOnline;
        /** Zeitpunkt der letzten Speicherung der Onlinezeit. */
        public volatile long lastFlush;
        public final Set<String> notifyOff = ConcurrentHashMap.newKeySet();
        public volatile boolean chatOff;
        /** Ressourcenpaket in dieser Sitzung geladen (dann beim Serverwechsel nicht erneut senden). */
        public volatile boolean packLoaded;
        public volatile boolean loaded;

        Session(UUID uuid, String name, String ip) {
            this.uuid = uuid;
            this.name = name;
            this.ip = ip;
            this.joined = System.currentTimeMillis();
            this.lastFlush = joined;
        }

        public long onlineTime() {
            return storedOnline + (System.currentTimeMillis() - lastFlush);
        }
    }

    private final BungeeSystem plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public PlayerManager(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    public static String ip(ProxiedPlayer p) {
        if (p.getSocketAddress() instanceof InetSocketAddress a && a.getAddress() != null) return a.getAddress().getHostAddress();
        return "";
    }

    /**
     * Lokale/private Adresse (127.0.0.1, 192.168.x, 10.x …)? Dann ist sie für IP-Bans & Zweitaccount-Erkennung wertlos –
     * z. B. wenn alle über localhost oder dasselbe Heimnetz verbinden.
     */
    public static boolean isLocal(String ip) {
        if (ip == null || ip.isBlank()) return true;
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(ip);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress();
        } catch (Exception e) {
            return true;
        }
    }

    public Session session(UUID uuid) {
        return sessions.get(uuid);
    }

    public Session session(ProxiedPlayer p) {
        return sessions.computeIfAbsent(p.getUniqueId(), u -> new Session(u, p.getName(), ip(p)));
    }

    // ---------------------------------------------------------------- Join / Quit

    public void join(ProxiedPlayer p) {
        Session s = new Session(p.getUniqueId(), p.getName(), ip(p));
        sessions.put(p.getUniqueId(), s);
        long now = System.currentTimeMillis();
        plugin.db().run(con -> {
            long online = 0;
            String notify = "";
            boolean chatOff = false;
            boolean exists = false;
            try (PreparedStatement ps = con.prepareStatement("select onlinetime, notify, chat_off from bs_players where uuid = ?")) {
                ps.setString(1, s.uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        exists = true;
                        online = rs.getLong(1);
                        notify = rs.getString(2);
                        chatOff = rs.getBoolean(3);
                    }
                }
            }
            if (exists) {
                Database.exec(con, "update bs_players set name = ?, ip = ?, last_join = ? where uuid = ?", s.name, s.ip, now, s.uuid.toString());
            } else {
                Database.exec(con, "insert into bs_players (uuid, name, ip, first_join, last_join) values (?, ?, ?, ?, ?)",
                        s.uuid.toString(), s.name, s.ip, now, now);
            }
            // Zeile im LobbySystem anlegen, damit Freunde/Coins auch ohne Lobby-Besuch funktionieren
            if (!Database.exists(con, "select 1 from ls_players where uuid = ?", s.uuid.toString())) {
                Database.exec(con, "insert into ls_players (uuid, name, settings, selected, owned, last_seen) values (?, ?, '', '', '', ?)",
                        s.uuid.toString(), s.name, now);
            } else {
                Database.exec(con, "update ls_players set name = ? where uuid = ?", s.name, s.uuid.toString());
            }
            s.storedOnline = online;
            s.chatOff = chatOff;
            for (String part : notify.split(";")) {
                if (part.endsWith("=0")) s.notifyOff.add(part.substring(0, part.length() - 2));
            }
            s.loaded = true;
            return null;
        });
    }

    public void quit(ProxiedPlayer p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) flush(s);
        long now = System.currentTimeMillis();
        String id = p.getUniqueId().toString();
        plugin.db().run(con -> Database.exec(con, "update ls_players set online_server = '', last_seen = ? where uuid = ?", now, id));
    }

    /** Onlinezeit seit der letzten Speicherung schreiben. */
    public void flush(Session s) {
        long now = System.currentTimeMillis();
        long delta = now - s.lastFlush;
        if (delta <= 0) return;
        s.lastFlush = now;
        s.storedOnline += delta;
        String id = s.uuid.toString();
        plugin.db().run(con -> Database.exec(con, "update bs_players set onlinetime = onlinetime + ? where uuid = ?", delta, id));
    }

    public void flushAll() {
        for (Session s : sessions.values()) flush(s);
    }

    public void saveSettings(Session s) {
        StringBuilder sb = new StringBuilder();
        for (String off : s.notifyOff) sb.append(off).append("=0;");
        String notify = sb.toString(), id = s.uuid.toString();
        boolean chatOff = s.chatOff;
        plugin.db().run(con -> Database.exec(con, "update bs_players set notify = ?, chat_off = ? where uuid = ?", notify, chatOff, id));
    }

    // ---------------------------------------------------------------- Suche

    /** Spieler über den Namen finden – online, sonst bs_players, sonst ls_players. */
    public CompletableFuture<Optional<Known>> lookup(String name) {
        ProxiedPlayer online = ProxyServer.getInstance().getPlayer(name);
        if (online != null) return CompletableFuture.completedFuture(Optional.of(new Known(online.getUniqueId(), online.getName(), ip(online))));
        return plugin.db().submit(con -> {
            try (PreparedStatement ps = con.prepareStatement("select uuid, name, ip from bs_players where lower(name) = lower(?) order by last_join desc limit 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(new Known(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3)));
                }
            }
            try (PreparedStatement ps = con.prepareStatement("select uuid, name from ls_players where lower(name) = lower(?) limit 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(new Known(UUID.fromString(rs.getString(1)), rs.getString(2), ""));
                }
            }
            return Optional.<Known>empty();
        });
    }

    public CompletableFuture<String> name(UUID uuid) {
        ProxiedPlayer online = ProxyServer.getInstance().getPlayer(uuid);
        if (online != null) return CompletableFuture.completedFuture(online.getName());
        return plugin.db().submit(con -> {
            String n = Database.string(con, "select name from bs_players where uuid = ?", uuid.toString());
            if (n == null) n = Database.string(con, "select name from ls_players where uuid = ?", uuid.toString());
            return n == null ? uuid.toString().substring(0, 8) : n;
        });
    }

    /** Accounts mit derselben IP (für /check). */
    public CompletableFuture<List<String>> alts(String ip, UUID except) {
        if (ip == null || ip.isBlank() || isLocal(ip)) return CompletableFuture.completedFuture(List.of());
        return plugin.db().submit(con -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("select name from bs_players where ip = ? and uuid <> ? order by last_join desc limit 15")) {
                ps.setString(1, ip);
                ps.setString(2, except.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    public CompletableFuture<long[]> joinInfo(UUID uuid) {
        return plugin.db().submit(con -> {
            try (PreparedStatement ps = con.prepareStatement("select first_join, last_join, onlinetime from bs_players where uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)};
                }
            }
            return new long[]{0, 0, 0};
        });
    }
}
