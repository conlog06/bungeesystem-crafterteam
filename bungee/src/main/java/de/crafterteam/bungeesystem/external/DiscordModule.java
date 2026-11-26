package de.crafterteam.bungeesystem.external;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.util.Text;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.config.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord über Webhooks (kein Bot-Token, keine extra Library):
 * <ul>
 *   <li>Status-Nachricht mit MOTD, Spielerzahl & Servern – wird regelmäßig bearbeitet statt neu gesendet</li>
 *   <li>Log-Embeds bei Ban, Mute, Kick (und optional Reports/Chat-Filter)</li>
 * </ul>
 */
public final class DiscordModule {

    private static final Pattern MESSAGE_ID = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"");

    private final BungeeSystem plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ScheduledTask task;

    public DiscordModule(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Configuration cfg() {
        return plugin.config().getSection("discord");
    }

    public void start() {
        String status = cfg().getString("status-webhook", "");
        if (!status.isBlank()) {
            int interval = Math.max(15, cfg().getInt("status-interval-seconds", 60));
            task = ProxyServer.getInstance().getScheduler().schedule(plugin, this::updateStatus, 5, interval, TimeUnit.SECONDS);
        }
        plugin.getLogger().info("Discord-Modul aktiv (Status: " + (status.isBlank() ? "aus" : "an") + ", Log: "
                + (cfg().getString("log-webhook", "").isBlank() ? "aus" : "an") + ").");
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    // ---------------------------------------------------------------- Log

    /** Aufgerufen vom NotifyManager für jede Team-Benachrichtigung. */
    public void onNotify(String type, String plainText) {
        if (!plugin.moduleActive("discord")) return;
        String url = cfg().getString("log-webhook", "");
        if (url.isBlank() || !cfg().getStringList("log-types").contains(type)) return;
        String title = cfg().getString("log-titles." + type, type.toUpperCase());
        plainText = plainText.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "").trim();
        int color = switch (type) {
            case "ban" -> 0xFF4B2B;
            case "mute" -> 0xF7971E;
            case "kick" -> 0xE94057;
            case "report" -> 0x8E2DE2;
            default -> 0x3A7BD5;
        };
        String json = "{\"username\":" + q(cfg().getString("username", "CrafterTeam")) + ",\"embeds\":[{\"title\":" + q(title)
                + ",\"description\":" + q(plainText) + ",\"color\":" + color + ",\"timestamp\":" + q(Instant.now().toString()) + "}]}";
        post(url, json);
    }

    // ---------------------------------------------------------------- Status

    private String statusJson() {
        ProxyServer proxy = ProxyServer.getInstance();
        boolean maintenance = plugin.network() != null && plugin.network().maintenance();
        String kind = maintenance ? "maintenance" : "normal";
        Configuration motd = plugin.config().getSection("motd." + kind);
        String line1 = plugin.settings().get("motd." + kind + ".1", motd.getString("line1", ""));
        String line2 = plugin.settings().get("motd." + kind + ".2", motd.getString("line2", ""));
        String motdText = Text.strip(line1.replace("[>]", "»").replace("[o]", "●")) + "\n" + Text.strip(line2.replace("[>]", "»").replace("[o]", "●"));
        int max = proxy.getConfig().getPlayerLimit();
        List<ServerInfo> servers = new ArrayList<>(proxy.getServers().values());
        servers.sort(Comparator.comparing((ServerInfo s) -> -s.getPlayers().size()));
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (ServerInfo s : servers) {
            if (s.getPlayers().isEmpty() || shown++ >= 15) continue;
            list.append("**").append(s.getName()).append("** – ").append(s.getPlayers().size()).append('\n');
        }
        if (list.isEmpty()) list.append("-");
        String fields = "[{\"name\":\"Status\",\"value\":" + q(maintenance ? "🔧 Wartungsarbeiten" : "🟢 Online") + ",\"inline\":true},"
                + "{\"name\":\"Spieler\",\"value\":" + q(proxy.getOnlineCount() + (max > 0 ? " / " + max : "")) + ",\"inline\":true},"
                + "{\"name\":\"IP\",\"value\":" + q(cfg().getString("server-ip", "play.crafterteam.de")) + ",\"inline\":true},"
                + "{\"name\":\"Server\",\"value\":" + q(list.toString()) + ",\"inline\":false}]";
        String icon = cfg().getString("icon-url", "");
        return "{\"username\":" + q(cfg().getString("username", "CrafterTeam")) + ",\"embeds\":[{\"title\":" + q(cfg().getString("status-title", "CrafterTeam Netzwerk"))
                + ",\"description\":" + q("```\n" + motdText + "\n```") + ",\"color\":" + (maintenance ? 0xF7971E : 0x56AB2F)
                + ",\"fields\":" + fields + (icon.isBlank() ? "" : ",\"thumbnail\":{\"url\":" + q(icon) + "}")
                + ",\"footer\":{\"text\":\"Zuletzt aktualisiert\"},\"timestamp\":" + q(Instant.now().toString()) + "}]}";
    }

    private void updateStatus() {
        if (!plugin.moduleActive("discord")) return;
        String url = cfg().getString("status-webhook", "");
        if (url.isBlank()) return;
        String json = statusJson();
        String id = plugin.settings().get("discord.status-message", "");
        String base = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (!id.isBlank()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/messages/" + id)).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
                if (r.statusCode() == 404) {
                    plugin.settings().set("discord.status-message", null); // Nachricht gelöscht → neu senden
                } else if (r.statusCode() >= 300) {
                    plugin.getLogger().warning("Discord-Status: HTTP " + r.statusCode());
                }
            });
            return;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "?wait=true")).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
            Matcher m = MESSAGE_ID.matcher(r.body());
            if (r.statusCode() < 300 && m.find()) plugin.settings().set("discord.status-message", m.group(1));
            else plugin.getLogger().warning("Discord-Status: HTTP " + r.statusCode());
        });
    }

    private void post(String url, String json) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).thenAccept(r -> {
            if (r.statusCode() >= 300) plugin.getLogger().warning("Discord-Log: HTTP " + r.statusCode());
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Discord nicht erreichbar: " + ex.getMessage());
            return null;
        });
    }

    /** JSON-String mit Anführungszeichen. */
    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> {
                }
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
