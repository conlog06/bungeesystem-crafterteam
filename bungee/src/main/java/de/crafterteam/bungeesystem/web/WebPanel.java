package de.crafterteam.bungeesystem.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.crafterteam.bungeesystem.BungeeSystem;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Web-Dashboard (nur lesend): Spieler & Server live, Verlauf der letzten 24 h, letzte Strafen, offene Reports & Tickets.
 * Zugriff nur mit Token (config.yml → webpanel.token, wird beim ersten Start erzeugt).
 */
public final class WebPanel {

    private final BungeeSystem plugin;
    private HttpServer server;
    /** Spielerzahl pro Minute, letzte 24 h (für das Diagramm). */
    private final Deque<int[]> history = new ArrayDeque<>();
    private final Map<String, long[]> rate = new ConcurrentHashMap<>();
    private volatile String cachedJson = "{}";
    private volatile long cachedAt;

    public WebPanel(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Configuration cfg() {
        return plugin.config().getSection("webpanel");
    }

    public String token() {
        return cfg().getString("token", "");
    }

    public String url() {
        return "http://" + cfg().getString("public-host", "localhost") + ":" + cfg().getInt("port", 8166) + "/?token=" + token();
    }

    public void start() {
        if (token().length() < 16) {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            plugin.config().set("webpanel.token", HexFormat.of().formatHex(b));
            plugin.configFile().save();
        }
        try {
            server = HttpServer.create(new InetSocketAddress(cfg().getString("host", "0.0.0.0"), cfg().getInt("port", 8166)), 0);
        } catch (IOException e) {
            plugin.getLogger().warning("Web-Dashboard: Port " + cfg().getInt("port", 8166) + " nicht frei – " + e.getMessage());
            return;
        }
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "BungeeSystem-Web");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
        ProxyServer.getInstance().getScheduler().schedule(plugin, this::sample, 0, 1, TimeUnit.MINUTES);
        plugin.getLogger().info("Web-Dashboard: " + url());
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void sample() {
        synchronized (history) {
            history.addLast(new int[]{(int) (System.currentTimeMillis() / 60_000L), ProxyServer.getInstance().getOnlineCount()});
            while (history.size() > 24 * 60) history.pollFirst();
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();
            long[] r = rate.computeIfAbsent(ip, k -> new long[]{System.currentTimeMillis(), 0});
            synchronized (r) {
                if (System.currentTimeMillis() - r[0] > 60_000L) {
                    r[0] = System.currentTimeMillis();
                    r[1] = 0;
                }
                if (++r[1] > 120) {
                    send(ex, 429, "text/plain", "Zu viele Anfragen");
                    return;
                }
            }
            if (path.equals("/") || path.equals("/index.html")) {
                try (InputStream in = plugin.getResourceAsStream("web/index.html")) {
                    send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                return;
            }
            if (path.equals("/api/overview")) {
                if (!authorized(ex)) {
                    send(ex, 401, "application/json", "{\"error\":\"Token ungültig\"}");
                    return;
                }
                send(ex, 200, "application/json; charset=utf-8", overview());
                return;
            }
            send(ex, 404, "text/plain", "Nicht gefunden");
        } catch (Exception e) {
            send(ex, 500, "text/plain", "Fehler");
        }
    }

    private boolean authorized(HttpExchange ex) {
        String given = ex.getRequestHeaders().getFirst("X-Token");
        if (given == null) {
            String q = ex.getRequestURI().getRawQuery();
            if (q != null) for (String part : q.split("&")) {
                if (part.startsWith("token=")) given = URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);
            }
        }
        if (given == null) return false;
        // zeitkonstanter Vergleich
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    // ---------------------------------------------------------------- Daten

    /** JSON für das Dashboard – max. alle 5 s neu gebaut (Performance bei mehreren offenen Tabs). */
    private String overview() throws Exception {
        if (System.currentTimeMillis() - cachedAt < 5000L) return cachedJson;
        ProxyServer proxy = ProxyServer.getInstance();
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"online\":").append(proxy.getOnlineCount()).append(',');
        sb.append("\"max\":").append(proxy.getConfig().getPlayerLimit()).append(',');
        sb.append("\"maintenance\":").append(plugin.network() != null && plugin.network().maintenance()).append(',');
        // Server
        List<ServerInfo> servers = new ArrayList<>(proxy.getServers().values());
        servers.sort(Comparator.comparing(ServerInfo::getName));
        sb.append("\"servers\":[");
        for (int i = 0; i < servers.size(); i++) {
            ServerInfo s = servers.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(q(s.getName())).append(",\"players\":").append(s.getPlayers().size()).append('}');
        }
        sb.append("],\"players\":[");
        int i = 0;
        for (ProxiedPlayer p : proxy.getPlayers()) {
            if (i++ > 0) sb.append(',');
            var session = plugin.players().session(p.getUniqueId());
            sb.append("{\"name\":").append(q(p.getName())).append(",\"server\":").append(q(p.getServer() == null ? "-" : p.getServer().getInfo().getName()))
                    .append(",\"ping\":").append(p.getPing()).append(",\"online\":").append(session == null ? 0 : session.onlineTime() / 60_000L).append('}');
        }
        sb.append("],\"history\":[");
        synchronized (history) {
            int j = 0;
            for (int[] h : history) {
                if (j++ > 0) sb.append(',');
                sb.append('[').append((long) h[0] * 60_000L).append(',').append(h[1]).append(']');
            }
        }
        sb.append("],");
        String rest = plugin.db().submit(con -> {
            StringBuilder d = new StringBuilder();
            d.append("\"bans\":").append(rows(con, "select player_name, staff_name, reason, banned_at, until_time, active from bs_bans order by id desc limit 15",
                    new String[]{"name", "staff", "reason", "created", "until", "active"}));
            d.append(",\"mutes\":").append(rows(con, "select player_name, staff_name, reason, muted_at, until_time, active from bs_mutes order by id desc limit 15",
                    new String[]{"name", "staff", "reason", "created", "until", "active"}));
            d.append(",\"reports\":").append(rows(con, "select id, target_name, reporter_name, reason, server, created, status, handler from bs_reports "
                    + "where status <> 'CLOSED' order by id desc limit 30", new String[]{"id", "target", "reporter", "reason", "server", "created", "status", "handler"}));
            d.append(",\"tickets\":").append(rows(con, "select id, player_name, subject, status, assignee, updated from bs_tickets where status <> 'CLOSED' "
                    + "order by updated desc limit 30", new String[]{"id", "name", "subject", "status", "assignee", "updated"}));
            d.append(",\"totals\":").append(rows(con, "select (select count(*) from bs_players), (select count(*) from bs_bans where active = 1), "
                    + "(select count(*) from bs_mutes where active = 1), (select count(*) from bs_reports where status = 'OPEN'), "
                    + "(select count(*) from bs_tickets where status = 'OPEN')", new String[]{"players", "bans", "mutes", "reports", "tickets"}));
            return d.toString();
        }).get(5, TimeUnit.SECONDS);
        sb.append(rest).append('}');
        cachedJson = sb.toString();
        cachedAt = System.currentTimeMillis();
        return cachedJson;
    }

    private static String rows(java.sql.Connection con, String sql, String[] names) throws java.sql.SQLException {
        StringBuilder sb = new StringBuilder("[");
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            int n = 0;
            while (rs.next()) {
                if (n++ > 0) sb.append(',');
                sb.append('{');
                for (int c = 0; c < names.length; c++) {
                    if (c > 0) sb.append(',');
                    Object v = rs.getObject(c + 1);
                    sb.append(q(names[c])).append(':').append(v instanceof Number num ? num.toString() : v instanceof Boolean b ? b.toString() : v == null ? "null" : q(v.toString()));
                }
                sb.append('}');
            }
        }
        return sb.append(']').toString();
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
