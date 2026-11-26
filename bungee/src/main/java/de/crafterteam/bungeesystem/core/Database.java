package de.crafterteam.bungeesystem.core;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * MySQL – dieselbe Datenbank wie LobbySystem & MissileWars. Jeder Worker-Thread hat eine eigene Verbindung,
 * der Proxy-Thread wird nie blockiert.
 */
public final class Database {

    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection con) throws SQLException;
    }

    private final Plugin plugin;
    private final String url, user, password;
    private final ExecutorService executor;
    private final List<Connection> all = new ArrayList<>();
    private final ThreadLocal<Connection> local = new ThreadLocal<>();

    public Database(Plugin plugin, Configuration c) {
        this.plugin = plugin;
        String props = c.getString("properties", "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC");
        this.url = "jdbc:mysql://" + c.getString("host", "localhost") + ":" + c.getInt("port", 3306) + "/"
                + c.getString("database", "lobby") + (props.isBlank() ? "" : "?" + props);
        this.user = c.getString("user", c.getString("username", "root"));
        this.password = c.getString("password", "");
        int threads = Math.max(1, Math.min(10, c.getInt("pool-size", 3)));
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "BungeeSystem-DB-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        for (String driver : new String[]{"de.crafterteam.bungeesystem.libs.mysql.cj.jdbc.Driver", "com.mysql.cj.jdbc.Driver"}) {
            try {
                Class.forName(driver);
                break;
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private Connection connection() throws SQLException {
        Connection con = local.get();
        if (con != null && con.isValid(2)) return con;
        if (con != null) {
            try {
                con.close();
            } catch (SQLException ignored) {
            }
        }
        con = DriverManager.getConnection(url, user, password);
        local.set(con);
        synchronized (all) {
            all.add(con);
        }
        return con;
    }

    public <T> CompletableFuture<T> submit(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run(connection());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Datenbank-Fehler: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    public void run(SqlTask<?> task) {
        submit(task);
    }

    // ---------------------------------------------------------------- Helfer

    public static int exec(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }

    public static boolean exists(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static long longValue(Connection con, String sql, long def, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : def;
            }
        }
    }

    public static String string(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---------------------------------------------------------------- Tabellen

    /** Legt alle Tabellen an – die ls_*-Tabellen identisch zum LobbySystem, die bs_*-Tabellen kompatibel zum alten BanSystem. */
    public void createTables() throws Exception {
        submit(con -> {
            try (Statement st = con.createStatement()) {
                // ---- BanSystem (alte Tabellen bleiben erhalten, neue Spalten werden ergänzt)
                st.executeUpdate("create table if not exists bs_bans (id int auto_increment primary key, uuid varchar(36) not null, "
                        + "player_name varchar(64) not null, staff_name varchar(64) not null, reason text not null, banned_at bigint not null, "
                        + "until_time bigint not null default -1, active tinyint(1) not null default 1, unbanned_by varchar(64) default null, "
                        + "index idx_uuid (uuid), index idx_active (active))");
                st.executeUpdate("create table if not exists bs_mutes (id int auto_increment primary key, uuid varchar(36) not null, "
                        + "player_name varchar(64) not null, staff_name varchar(64) not null, reason text not null, muted_at bigint not null, "
                        + "until_time bigint not null, active tinyint(1) not null default 1, index idx_uuid (uuid), index idx_active (active))");
                st.executeUpdate("create table if not exists bs_kicks (id int auto_increment primary key, uuid varchar(36) not null, "
                        + "player_name varchar(64) not null, staff_name varchar(64) not null, reason text not null, kicked_at bigint not null, "
                        + "index idx_uuid (uuid))");
                st.executeUpdate("create table if not exists bs_players (uuid varchar(36) not null primary key, name varchar(16) not null, "
                        + "ip varchar(45) not null default '', first_join bigint not null default 0, last_join bigint not null default 0, "
                        + "onlinetime bigint not null default 0, notify varchar(128) not null default '', chat_off tinyint(1) not null default 0, "
                        + "index idx_name (name), index idx_ip (ip))");
                st.executeUpdate("create table if not exists bs_reports (id int auto_increment primary key, reporter varchar(36) not null, "
                        + "reporter_name varchar(16) not null, target varchar(36) not null, target_name varchar(16) not null, reason varchar(128) not null, "
                        + "server varchar(64) not null default '', created bigint not null, status varchar(16) not null default 'OPEN', "
                        + "handler varchar(16) not null default '', handled_at bigint not null default 0, index idx_status (status))");
                st.executeUpdate("create table if not exists bs_settings (k varchar(64) not null primary key, v text not null)");
                st.executeUpdate("create table if not exists bs_warns (id int auto_increment primary key, uuid varchar(36) not null, "
                        + "player_name varchar(16) not null, staff_name varchar(64) not null, reason varchar(128) not null, created bigint not null, "
                        + "active tinyint(1) not null default 1, index idx_uuid (uuid))");
                st.executeUpdate("create table if not exists bs_bugreports (id int auto_increment primary key, uuid varchar(36) not null, "
                        + "player_name varchar(16) not null, server varchar(64) not null, message text not null, created bigint not null, "
                        + "status varchar(16) not null default 'OPEN', handler varchar(16) not null default '', index idx_status (status))");
                st.executeUpdate("create table if not exists bs_adverts (uuid varchar(36) not null primary key, referrer varchar(36) not null, "
                        + "created bigint not null, index idx_referrer (referrer))");
                st.executeUpdate("create table if not exists bs_ts_verify (uuid varchar(36) not null primary key, ts_uid varchar(64) not null, "
                        + "created bigint not null)");
                // ---- LobbySystem-Tabellen (falls der Proxy zuerst startet)
                st.executeUpdate("create table if not exists ls_players (uuid varchar(36) not null primary key, name varchar(16) not null, "
                        + "coins bigint not null default 0, lose int not null default 0, playtime bigint not null default 0, "
                        + "last_daily bigint not null default 0, last_premium bigint not null default 0, settings varchar(255) not null default '', "
                        + "selected varchar(255) not null default '', owned text)");
                st.executeUpdate("create table if not exists ls_friends (uuid varchar(36) not null, friend varchar(36) not null, since bigint not null, "
                        + "primary key (uuid, friend))");
                st.executeUpdate("create table if not exists ls_friend_requests (sender varchar(36) not null, receiver varchar(36) not null, "
                        + "created bigint not null, primary key (sender, receiver))");
                st.executeUpdate("create table if not exists ls_parties (id varchar(36) not null primary key, leader varchar(36) not null, created bigint not null)");
                st.executeUpdate("create table if not exists ls_party_members (uuid varchar(36) not null primary key, party_id varchar(36) not null, joined bigint not null)");
                st.executeUpdate("create table if not exists ls_party_invites (party_id varchar(36) not null, uuid varchar(36) not null, expires bigint not null, "
                        + "primary key (party_id, uuid))");
                st.executeUpdate("create table if not exists ls_clans (id varchar(36) not null primary key, name varchar(32) not null, tag varchar(8) not null, "
                        + "owner varchar(36) not null, created bigint not null)");
                st.executeUpdate("create table if not exists ls_clan_members (uuid varchar(36) not null primary key, clan_id varchar(36) not null, "
                        + "role int not null default 0, joined bigint not null)");
                st.executeUpdate("create table if not exists ls_clan_invites (clan_id varchar(36) not null, uuid varchar(36) not null, expires bigint not null, "
                        + "primary key (clan_id, uuid))");
                st.executeUpdate("create table if not exists ls_servers (name varchar(64) not null primary key, online int not null default 0, "
                        + "max_players int not null default 0, updated bigint not null default 0)");
            }
            addColumn(con, "bs_bans", "ip", "varchar(45) not null default ''");
            addColumn(con, "bs_bans", "reason_id", "varchar(32) not null default ''");
            addColumn(con, "bs_bans", "server", "varchar(64) not null default ''");
            addColumn(con, "bs_bans", "unbanned_at", "bigint not null default 0");
            addColumn(con, "bs_mutes", "reason_id", "varchar(32) not null default ''");
            addColumn(con, "bs_mutes", "server", "varchar(64) not null default ''");
            addColumn(con, "bs_mutes", "unmuted_by", "varchar(64) default null");
            addColumn(con, "bs_kicks", "server", "varchar(64) not null default ''");
            addColumn(con, "ls_players", "online_server", "varchar(64) not null default ''");
            addColumn(con, "ls_players", "last_seen", "bigint not null default 0");
            addColumn(con, "ls_players", "streak", "int not null default 0");
            addColumn(con, "ls_players", "friend_settings", "varchar(64) not null default ''");
            addColumn(con, "ls_servers", "state", "varchar(32) not null default ''");
            addColumn(con, "ls_servers", "game", "varchar(32) not null default ''");
            return null;
        }).get(20, TimeUnit.SECONDS);
    }

    private static void addColumn(Connection con, String table, String column, String definition) throws SQLException {
        try (ResultSet rs = con.getMetaData().getColumns(con.getCatalog(), null, table, null)) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return;
        }
        try (Statement st = con.createStatement()) {
            st.executeUpdate("alter table " + table + " add column " + column + " " + definition);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (all) {
            for (Connection c : all) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }
}
