package de.crafterteam.bungeesystem.network;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.core.Settings;
import de.crafterteam.bungeesystem.util.Text;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wartung (Whitelist, Release-Countdown), MOTD, /hub, Server-Befehle, Broadcast & AutoBroadcast, /help, Links,
 * /ping, /online, /whereami, /debugreset.
 */
public final class NetworkModule implements Listener {

    private final BungeeSystem plugin;
    private final AtomicInteger broadcastIndex = new AtomicInteger();

    public NetworkModule(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Settings st() {
        return plugin.settings();
    }

    private Configuration cfg(String path) {
        return plugin.config().getSection(path);
    }

    private static String symbols(String s) {
        return s.replace("[>]", "»").replace("[<]", "«").replace("[o]", "●").replace("[*]", "✦").replace("[|]", "┃");
    }

    // ================================================================ Wartung

    public boolean maintenance() {
        if (!plugin.moduleActive("maintenance") || !st().bool("maintenance.enabled")) return false;
        long until = st().number("maintenance.until", 0);
        if (until > 0 && System.currentTimeMillis() > until) {
            st().set("maintenance.enabled", "false");
            st().set("maintenance.until", null);
            plugin.getLogger().info("Wartung automatisch beendet (Release-Zeit erreicht).");
            return false;
        }
        return true;
    }

    private Set<String> whitelist() {
        Set<String> out = new LinkedHashSet<>();
        for (String n : st().get("maintenance.whitelist", "").split(",")) if (!n.isBlank()) out.add(n.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private void saveWhitelist(Set<String> names) {
        st().set("maintenance.whitelist", String.join(",", names));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(LoginEvent e) {
        if (e.isCancelled() || !maintenance()) return;
        if (whitelist().contains(e.getConnection().getName().toLowerCase(Locale.ROOT))) return;
        e.setCancelled(true);
        long until = st().number("maintenance.until", 0);
        e.setCancelReason(TextComponent.fromLegacyText(m().legacy(until > 0 ? "maintenance.kick-release" : "maintenance.kick",
                "remaining", until > 0 ? Time.format(until - System.currentTimeMillis()) : "", "date", Time.date(until))));
    }

    @EventHandler
    public void onPing(ProxyPingEvent e) {
        ServerPing ping = e.getResponse();
        if (ping == null) return;
        boolean maint = maintenance();
        if (plugin.moduleActive("motd") || maint) {
            String kind = maint ? "maintenance" : "normal";
            Configuration def = cfg("motd." + kind);
            String l1 = st().get("motd." + kind + ".1", def.getString("line1", "")), l2 = st().get("motd." + kind + ".2", def.getString("line2", ""));
            String text = Text.replace(symbols(l1 + "\n" + l2), "online", Integer.toString(ping.getPlayers() == null ? 0 : ping.getPlayers().getOnline()),
                    "max", Integer.toString(ping.getPlayers() == null ? 0 : ping.getPlayers().getMax()));
            ping.setDescriptionComponent(new TextComponent(TextComponent.fromLegacyText(Text.color(text))));
        }
        if (maint) {
            long until = st().number("maintenance.until", 0);
            String version = until > 0
                    ? Text.replace(cfg("motd.maintenance").getString("version-release", "&cRelease in {remaining}"), "remaining", Time.format(until - System.currentTimeMillis()))
                    : cfg("motd.maintenance").getString("version", "&4✖ Wartungsarbeiten");
            ping.setVersion(new ServerPing.Protocol(Text.color(symbols(version)), -1));
        }
        e.setResponse(ping);
    }

    public void registerMaintenance() {
        plugin.command("maintenance", n -> new Cmd(plugin, n, "bungeesystem.maintenance") {
            @Override
            protected void run(CommandSender s, String[] args) {
                String sub = args.length > 0 ? args[0].toLowerCase() : "";
                switch (sub) {
                    case "on", "an" -> {
                        st().set("maintenance.enabled", "true");
                        st().set("maintenance.until", null);
                        m().send(s, "maintenance.on");
                        kickNonWhitelisted();
                    }
                    case "off", "aus" -> {
                        st().set("maintenance.enabled", "false");
                        st().set("maintenance.until", null);
                        m().send(s, "maintenance.off");
                    }
                    case "set" -> {
                        long d = args.length > 1 ? Time.parse(join(args, 1).replace(" ", "")) : -2;
                        if (d <= 0) {
                            m().send(s, "maintenance.usage", "cmd", getName());
                            return;
                        }
                        st().set("maintenance.enabled", "true");
                        st().set("maintenance.until", Long.toString(System.currentTimeMillis() + d));
                        m().send(s, "maintenance.set", "duration", Time.format(d), "date", Time.date(System.currentTimeMillis() + d));
                        kickNonWhitelisted();
                    }
                    case "add", "remove" -> {
                        if (args.length < 2) {
                            m().send(s, "maintenance.usage", "cmd", getName());
                            return;
                        }
                        Set<String> wl = whitelist();
                        String name = args[1].toLowerCase(Locale.ROOT);
                        boolean changed = sub.equals("add") ? wl.add(name) : wl.remove(name);
                        saveWhitelist(wl);
                        m().send(s, sub.equals("add") ? (changed ? "maintenance.added" : "maintenance.already") : (changed ? "maintenance.removed" : "maintenance.not-listed"),
                                "player", args[1]);
                    }
                    case "list" -> m().send(s, "maintenance.list", "players", whitelist().isEmpty() ? "-" : String.join(", ", whitelist()));
                    default -> {
                        long until = st().number("maintenance.until", 0);
                        m().send(s, "maintenance.status", "state", m().raw(maintenance() ? "general.on" : "general.off"),
                                "until", until > 0 ? Time.date(until) : "-", "count", Integer.toString(whitelist().size()));
                        m().send(s, "maintenance.usage", "cmd", getName());
                    }
                }
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                if (args.length == 1) return filter(args[0], "on", "off", "set", "add", "remove", "list");
                if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) return players(args[1]);
                if (args.length == 2 && args[0].equalsIgnoreCase("set")) return filter(args[1], "1h", "1d", "7d", "10d");
                return List.of();
            }
        });
    }

    private void kickNonWhitelisted() {
        Set<String> wl = whitelist();
        for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
            if (!wl.contains(p.getName().toLowerCase(Locale.ROOT))) p.disconnect(TextComponent.fromLegacyText(m().legacy("maintenance.kick")));
        }
    }

    // ================================================================ MOTD

    public void registerMotd() {
        plugin.command("motd", n -> new Cmd(plugin, n, "bungeesystem.motd") {
            @Override
            protected void run(CommandSender s, String[] args) {
                if (args.length < 3 || !List.of("normal", "wartung", "maintenance").contains(args[0].toLowerCase()) || !List.of("1", "2").contains(args[1])) {
                    m().send(s, "motd.usage", "cmd", getName());
                    return;
                }
                String kind = args[0].equalsIgnoreCase("normal") ? "normal" : "maintenance";
                String text = join(args, 2);
                st().set("motd." + kind + "." + args[1], text);
                m().send(s, "motd.set", "kind", kind, "line", args[1], "text", Text.color(symbols(text)));
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                if (args.length == 1) return filter(args[0], "normal", "wartung");
                if (args.length == 2) return filter(args[1], "1", "2");
                return List.of();
            }
        });
    }

    // ================================================================ Hub

    private List<ServerInfo> lobbies() {
        List<ServerInfo> out = new ArrayList<>();
        List<String> patterns = cfg("hub").getStringList("servers");
        for (ServerInfo s : ProxyServer.getInstance().getServers().values()) if (plugin.matches(s.getName(), patterns)) out.add(s);
        out.sort(Comparator.comparingInt(s -> s.getPlayers().size()));
        return out;
    }

    public void registerHub() {
        plugin.command("hub", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                ProxiedPlayer p = player(s);
                if (p == null) return;
                if (p.getServer() != null && plugin.matches(p.getServer().getInfo().getName(), cfg("hub").getStringList("servers"))) {
                    // schon in einer Lobby → das LobbySystem teleportiert zum Spawn
                    plugin.bridge().send(p, "SPAWN", out -> out.writeUTF(p.getUniqueId().toString()));
                    m().send(p, "hub.already");
                    return;
                }
                List<ServerInfo> list = lobbies();
                if (list.isEmpty()) {
                    m().send(p, "hub.none");
                    return;
                }
                tryConnect(p, list, 0);
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    private void tryConnect(ProxiedPlayer p, List<ServerInfo> list, int i) {
        if (i >= list.size()) {
            m().send(p, "hub.none");
            return;
        }
        ServerInfo target = list.get(i);
        m().send(p, "hub.connecting", "server", target.getName());
        p.connect(target, (ok, err) -> {
            if (!ok && p.isConnected()) tryConnect(p, list, i + 1);
        });
    }

    // ================================================================ Server-Befehle (/bauserver …)

    public void registerServers() {
        Configuration sec = cfg("server-commands");
        for (String key : sec.getKeys()) {
            Configuration c = sec.getSection(key);
            String server = c.getString("server", key);
            String perm = c.getString("permission", "");
            List<String> names = new ArrayList<>();
            names.add(key);
            names.addAll(c.getStringList("aliases"));
            plugin.command("server-" + key, names, n -> new Cmd(plugin, n, perm.isBlank() ? null : perm) {
                @Override
                protected void run(CommandSender s, String[] args) {
                    ProxiedPlayer p = player(s);
                    if (p == null) return;
                    ServerInfo info = ProxyServer.getInstance().getServerInfo(server);
                    if (info == null) {
                        m().send(p, "general.server-not-found", "server", server);
                        return;
                    }
                    if (p.getServer() != null && p.getServer().getInfo().equals(info)) {
                        m().send(p, "servers.already", "server", server);
                        return;
                    }
                    m().send(p, "servers.connecting", "server", server);
                    p.connect(info);
                }

                @Override
                protected List<String> complete(CommandSender s, String[] args) {
                    return List.of();
                }
            });
        }
    }

    // ================================================================ Broadcast

    public void registerBroadcast() {
        plugin.command("broadcast", n -> new Cmd(plugin, n, "bungeesystem.broadcast") {
            @Override
            protected void run(CommandSender s, String[] args) {
                if (args.length == 0) {
                    m().send(s, "broadcast.usage", "cmd", getName());
                    return;
                }
                // Teammitglieder dürfen Farben & Buttons nutzen – daher roh einsetzen
                String text = m().raw("broadcast.format", "staff", name(s)).replace("{message}", symbols(join(args, 0)));
                for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) Messages.sendRaw(p, text);
                if (!(s instanceof ProxiedPlayer)) Messages.sendRaw(s, text);
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    public void startAutoBroadcast() {
        int interval = Math.max(10, cfg("autobroadcast").getInt("interval-seconds", 300));
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            if (!plugin.moduleActive("autobroadcast") || ProxyServer.getInstance().getOnlineCount() == 0) return;
            List<?> list = cfg("autobroadcast").getList("messages");
            if (list == null || list.isEmpty()) return;
            int i = cfg("autobroadcast").getBoolean("random", false) ? ThreadLocalRandom.current().nextInt(list.size())
                    : Math.floorMod(broadcastIndex.getAndIncrement(), list.size());
            Object entry = list.get(i);
            String text = entry instanceof List<?> lines ? String.join("\n", lines.stream().map(String::valueOf).toList()) : String.valueOf(entry);
            text = symbols(text.replace("{prefix}", m().prefix("autobroadcast")));
            List<String> ignored = cfg("autobroadcast").getStringList("ignored-servers");
            for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
                if (p.getServer() != null && plugin.matches(p.getServer().getInfo().getName(), ignored)) continue;
                Messages.sendRaw(p, text);
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    // ================================================================ Help & Links

    public void registerHelp() {
        plugin.command("help", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                List<String> pages = new ArrayList<>(cfg("help.pages").getKeys());
                if (pages.isEmpty()) return;
                int page = 1;
                if (args.length > 0) {
                    try {
                        page = Integer.parseInt(args[0]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                page = Math.max(1, Math.min(pages.size(), page));
                for (String line : cfg("help.pages").getStringList(pages.get(page - 1))) {
                    Messages.sendRaw(s, symbols(Text.replace(line, "page", Integer.toString(page), "pages", Integer.toString(pages.size()),
                            "next", Integer.toString(Math.min(pages.size(), page + 1)), "prev", Integer.toString(Math.max(1, page - 1)), "cmd", getName())));
                }
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    public void registerLinks() {
        Configuration sec = cfg("links");
        for (String key : sec.getKeys()) {
            Configuration c = sec.getSection(key);
            List<String> names = new ArrayList<>();
            names.add(key);
            names.addAll(c.getStringList("aliases"));
            List<String> lines = c.getStringList("lines");
            plugin.command("link-" + key, names, n -> new Cmd(plugin, n, null) {
                @Override
                protected void run(CommandSender s, String[] args) {
                    for (String l : lines) Messages.sendRaw(s, symbols(l.replace("{prefix}", m().prefix("links"))));
                }

                @Override
                protected List<String> complete(CommandSender s, String[] args) {
                    return List.of();
                }
            });
        }
    }

    // ================================================================ Ping, Online, WhereAmI, Debug

    public void registerPing() {
        plugin.command("ping", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                if (args.length > 0 && s.hasPermission("bungeesystem.ping.others")) {
                    ProxiedPlayer t = ProxyServer.getInstance().getPlayer(args[0]);
                    if (t == null) m().send(s, "general.player-offline", "player", args[0]);
                    else m().send(s, "ping.other", "player", t.getName(), "ping", Integer.toString(t.getPing()), "color", color(t.getPing()));
                    return;
                }
                ProxiedPlayer p = player(s);
                if (p != null) m().send(p, "ping.own", "ping", Integer.toString(p.getPing()), "color", color(p.getPing()));
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return args.length == 1 && s.hasPermission("bungeesystem.ping.others") ? players(args[0]) : List.of();
            }
        });
    }

    private static String color(int ping) {
        return ping < 60 ? "&a" : ping < 120 ? "&e" : ping < 250 ? "&6" : "&c";
    }

    public void registerOnline() {
        plugin.command("online", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                m().send(s, "online.total", "count", Integer.toString(ProxyServer.getInstance().getOnlineCount()));
                if (!s.hasPermission("bungeesystem.online.servers")) return;
                List<ServerInfo> servers = new ArrayList<>(ProxyServer.getInstance().getServers().values());
                servers.sort(Comparator.comparing(ServerInfo::getName));
                for (ServerInfo info : servers) {
                    if (!info.getPlayers().isEmpty()) m().send(s, "online.server", "server", info.getName(), "count", Integer.toString(info.getPlayers().size()));
                }
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    public void registerWhereAmI() {
        plugin.command("whereami", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                ProxiedPlayer p = player(s);
                if (p != null) m().send(p, "whereami.result", "server", p.getServer() == null ? "?" : p.getServer().getInfo().getName());
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    public void registerDebug() {
        plugin.command("debugreset", n -> new Cmd(plugin, n, "bungeesystem.debug") {
            @Override
            protected void run(CommandSender s, String[] args) {
                Set<String> online = new LinkedHashSet<>();
                for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) online.add(p.getUniqueId().toString());
                Set<String> active = new LinkedHashSet<>();
                for (ServerInfo i : ProxyServer.getInstance().getServers().values()) if (!i.getPlayers().isEmpty()) active.add(i.getName());
                plugin.db().submit(con -> {
                    int players = 0;
                    List<String> ids = new ArrayList<>();
                    try (var ps = con.prepareStatement("select uuid from ls_players where online_server <> ''"); var rs = ps.executeQuery()) {
                        while (rs.next()) ids.add(rs.getString(1));
                    }
                    for (String id : ids) if (!online.contains(id)) players += Database.exec(con, "update ls_players set online_server = '' where uuid = ?", id);
                    int servers = 0;
                    List<String> names = new ArrayList<>();
                    try (var ps = con.prepareStatement("select name from ls_servers where online > 0"); var rs = ps.executeQuery()) {
                        while (rs.next()) names.add(rs.getString(1));
                    }
                    for (String name : names) if (!active.contains(name)) servers += Database.exec(con, "update ls_servers set online = 0 where name = ?", name);
                    return new int[]{players, servers};
                }).thenAccept(r -> m().send(s, "debug.reset", "players", Integer.toString(r[0]), "servers", Integer.toString(r[1])));
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    @SuppressWarnings("unused")
    private static List<String> list(String... s) {
        return Arrays.asList(s);
    }
}
