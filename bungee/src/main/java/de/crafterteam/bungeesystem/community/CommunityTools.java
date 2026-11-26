package de.crafterteam.bungeesystem.community;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.core.PlayerManager;
import de.crafterteam.bungeesystem.punish.PunishCommands;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** JoinMe (mit Kopf im Chat), Advert (Werber-Belohnung), /coins, /rankinfo. */
public final class CommunityTools {

    private CommunityTools() {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    // ================================================================ JoinMe

    record JoinMe(String name, String server, long until) {
    }

    private static final Map<String, JoinMe> JOINMES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> JOINME_COOLDOWN = new ConcurrentHashMap<>();

    public static void registerJoinMe(BungeeSystem plugin) {
        plugin.command("joinme", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                ProxiedPlayer p = player(s);
                if (p == null) return;
                Configuration c = plugin.config().getSection("joinme");
                if (args.length >= 2 && args[0].equalsIgnoreCase("join")) {
                    JoinMe j = JOINMES.get(args[1].toLowerCase());
                    if (j == null || j.until() < System.currentTimeMillis()) {
                        m().send(p, "joinme.expired");
                        return;
                    }
                    ServerInfo info = ProxyServer.getInstance().getServerInfo(j.server());
                    if (info == null) return;
                    if (p.getServer() != null && p.getServer().getInfo().equals(info)) {
                        m().send(p, "joinme.same");
                        return;
                    }
                    m().send(p, "joinme.connecting", "player", j.name(), "server", j.server());
                    p.connect(info);
                    return;
                }
                if (!p.hasPermission("bungeesystem.joinme")) {
                    m().send(p, "general.no-permission");
                    return;
                }
                if (p.getServer() == null) return;
                String server = p.getServer().getInfo().getName();
                if (plugin.matches(server, c.getStringList("blocked-servers"))) {
                    m().send(p, "joinme.blocked", "server", server);
                    return;
                }
                long cd = c.getLong("cooldown-seconds", 300) * 1000L;
                long last = JOINME_COOLDOWN.getOrDefault(p.getUniqueId(), 0L);
                if (!p.hasPermission("bungeesystem.joinme.bypass") && last + cd > System.currentTimeMillis()) {
                    m().send(p, "joinme.cooldown", "seconds", Long.toString((last + cd - System.currentTimeMillis()) / 1000 + 1));
                    return;
                }
                JOINME_COOLDOWN.put(p.getUniqueId(), System.currentTimeMillis());
                JOINMES.put(p.getName().toLowerCase(), new JoinMe(p.getName(), server, System.currentTimeMillis() + c.getLong("valid-seconds", 300) * 1000L));
                String text = m().raw("joinme.message", "player", p.getName(), "server", server, "cmd", getName());
                head(plugin, p.getUniqueId(), c.getBoolean("head", true)).thenAccept(head -> {
                    List<String> lines = new ArrayList<>(List.of(text.split("\n")));
                    if (!head.isEmpty()) {
                        // Kopf links neben den Text setzen
                        List<String> merged = new ArrayList<>();
                        for (int i = 0; i < Math.max(head.size(), lines.size()); i++) {
                            String h = i < head.size() ? head.get(i) : "        ";
                            merged.add(h + " " + (i < lines.size() ? lines.get(i) : ""));
                        }
                        lines = merged;
                    }
                    String all = String.join("\n", lines);
                    for (ProxiedPlayer x : ProxyServer.getInstance().getPlayers()) {
                        if (x.getServer() != null && x.getServer().getInfo().getName().equals(server)) continue;
                        Messages.sendRaw(x, all);
                    }
                    m().send(p, "joinme.sent");
                });
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    /** 8×8-Kopf als farbige Blöcke (Bild von mc-heads.net). Leer bei Fehler oder head=false. */
    static CompletableFuture<List<String>> head(BungeeSystem plugin, UUID uuid, boolean enabled) {
        if (!enabled) return CompletableFuture.completedFuture(List.of());
        String url = plugin.config().getString("joinme.head-url", "https://mc-heads.net/avatar/{uuid}/8.png").replace("{uuid}", uuid.toString());
        return HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(r -> {
                    try {
                        if (r.statusCode() != 200) return List.<String>of();
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.body()));
                        if (img == null) return List.<String>of();
                        List<String> out = new ArrayList<>();
                        for (int y = 0; y < 8; y++) {
                            StringBuilder sb = new StringBuilder();
                            for (int x = 0; x < 8; x++) {
                                int rgb = img.getRGB(x * img.getWidth() / 8, y * img.getHeight() / 8) & 0xFFFFFF;
                                sb.append(ChatColor.of(String.format("#%06x", rgb))).append('█');
                            }
                            out.add(sb.toString());
                        }
                        return out;
                    } catch (Exception e) {
                        return List.<String>of();
                    }
                }).exceptionally(ex -> List.of());
    }

    // ================================================================ Advert

    public static void registerAdvert(BungeeSystem plugin) {
        plugin.command("advert", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                ProxiedPlayer p = player(s);
                if (p == null) return;
                Configuration c = plugin.config().getSection("advert");
                if (args.length == 0) {
                    m().send(p, "advert.usage", "cmd", getName(), "coins", Long.toString(c.getLong("reward-new", 250)));
                    return;
                }
                if (args[0].equalsIgnoreCase(p.getName())) {
                    m().send(p, "advert.self");
                    return;
                }
                PunishCommands.resolve(plugin, s, args[0], t -> plugin.db().submit(con -> {
                    if (Database.exists(con, "select 1 from bs_adverts where uuid = ?", p.getUniqueId().toString())) return "advert.already";
                    long myFirst = Database.longValue(con, "select first_join from bs_players where uuid = ?", 0, p.getUniqueId().toString());
                    long theirFirst = Database.longValue(con, "select first_join from bs_players where uuid = ?", 0, t.uuid().toString());
                    long maxAge = c.getLong("max-days", 7) * 86_400_000L;
                    if (myFirst > 0 && System.currentTimeMillis() - myFirst > maxAge) return "advert.too-late";
                    if (theirFirst <= 0 || theirFirst >= myFirst) return "advert.not-older";
                    String myIp = PlayerManager.ip(p);
                    String theirIp = Database.string(con, "select ip from bs_players where uuid = ?", t.uuid().toString());
                    if (myIp != null && !myIp.isBlank() && myIp.equals(theirIp)) return "advert.same-ip";
                    Database.exec(con, "insert into bs_adverts (uuid, referrer, created) values (?, ?, ?)", p.getUniqueId().toString(), t.uuid().toString(),
                            System.currentTimeMillis());
                    return "ok";
                }).thenAccept(result -> {
                    if (!result.equals("ok")) {
                        m().send(p, result, "player", t.name(), "days", Long.toString(c.getLong("max-days", 7)));
                        return;
                    }
                    long rNew = c.getLong("reward-new", 250), rRef = c.getLong("reward-referrer", 500);
                    plugin.bridge().addCoins(p.getUniqueId(), rNew);
                    plugin.bridge().addCoins(t.uuid(), rRef);
                    m().send(p, "advert.success", "player", t.name(), "coins", Long.toString(rNew));
                    m().send(ProxyServer.getInstance().getPlayer(t.uuid()), "advert.referrer", "player", p.getName(), "coins", Long.toString(rRef));
                }));
            }
        });
    }

    // ================================================================ Coins

    public static void registerCoins(BungeeSystem plugin) {
        plugin.command("coins", n -> new Cmd(plugin, n, null) {
            @Override
            protected void run(CommandSender s, String[] args) {
                String sub = args.length > 0 ? args[0].toLowerCase() : "";
                boolean admin = s.hasPermission("bungeesystem.coins.admin");
                if (admin && List.of("add", "remove", "set").contains(sub) && args.length >= 3) {
                    long amount;
                    try {
                        amount = Long.parseLong(args[2]);
                    } catch (NumberFormatException e) {
                        m().send(s, "coins.usage", "cmd", getName());
                        return;
                    }
                    if (amount < 0) {
                        m().send(s, "coins.usage", "cmd", getName());
                        return;
                    }
                    PunishCommands.resolve(plugin, s, args[1], t -> {
                        CompletableFuture<Boolean> f = switch (sub) {
                            case "add" -> plugin.bridge().addCoins(t.uuid(), amount);
                            case "remove" -> plugin.bridge().takeCoins(t.uuid(), amount);
                            default -> plugin.bridge().coins(t.uuid()).thenCompose(cur -> cur < 0 ? CompletableFuture.completedFuture(false)
                                    : cur > amount ? plugin.bridge().takeCoins(t.uuid(), cur - amount) : plugin.bridge().addCoins(t.uuid(), amount - cur));
                        };
                        f.thenCompose(ok -> plugin.bridge().coins(t.uuid()).thenApply(now -> new Object[]{ok, now})).thenAccept(r -> {
                            boolean ok = (Boolean) r[0];
                            m().send(s, ok ? "coins.changed" : "coins.failed", "player", t.name(), "coins", format((Long) r[1]));
                        });
                    });
                    return;
                }
                if ((sub.equals("get") && args.length >= 2 || args.length == 1 && !sub.isEmpty()) && admin) {
                    String target = sub.equals("get") ? args[1] : args[0];
                    PunishCommands.resolve(plugin, s, target, t -> plugin.bridge().coins(t.uuid())
                            .thenAccept(c -> m().send(s, "coins.other", "player", t.name(), "coins", format(c))));
                    return;
                }
                ProxiedPlayer p = player(s);
                if (p == null) return;
                plugin.bridge().coins(p.getUniqueId()).thenAccept(c -> m().send(p, "coins.own", "coins", format(c)));
                if (admin && args.length > 0) m().send(s, "coins.usage", "cmd", getName());
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                if (!s.hasPermission("bungeesystem.coins.admin")) return List.of();
                if (args.length == 1) return filter(args[0], "add", "remove", "set", "get");
                if (args.length == 2) return players(args[1]);
                return List.of();
            }
        });
    }

    static String format(long coins) {
        return coins < 0 ? "0" : String.format("%,d", coins).replace(',', '.');
    }

    // ================================================================ RankInfo

    public static void registerRankInfo(BungeeSystem plugin) {
        plugin.command("rankinfo", n -> new Cmd(plugin, n, "bungeesystem.rankinfo") {
            @Override
            protected void run(CommandSender s, String[] args) {
                Object lp = luckPerms();
                if (lp != null) {
                    try {
                        Object gm = call(lp, "getGroupManager");
                        if (args.length == 0) {
                            List<String> names = new ArrayList<>();
                            for (Object g : (java.util.Collection<?>) call(gm, "getLoadedGroups")) names.add((String) call(g, "getName"));
                            names.sort(String::compareToIgnoreCase);
                            m().send(s, "rankinfo.list", "ranks", String.join(", ", names), "cmd", getName());
                            return;
                        }
                        Object g = call(gm, "getGroup", args[0].toLowerCase());
                        if (g == null) {
                            m().send(s, "rankinfo.not-found", "rank", args[0]);
                            return;
                        }
                        Object meta = call(call(g, "getCachedData"), "getMetaData");
                        Object prefix = call(meta, "getPrefix"), suffix = call(meta, "getSuffix");
                        String display = (String) call(g, "getDisplayName");
                        OptionalInt weight = (OptionalInt) call(g, "getWeight");
                        m().send(s, "rankinfo.info", "rank", (String) call(g, "getName"), "display", display == null ? "-" : display,
                                "prefix", prefix == null ? "-" : prefix + "&r", "suffix", suffix == null ? "-" : suffix + "&r",
                                "weight", weight.isPresent() ? Integer.toString(weight.getAsInt()) : "-");
                        return;
                    } catch (Exception e) {
                        plugin.getLogger().warning("RankInfo (LuckPerms): " + e);
                    }
                }
                Configuration groups = plugin.config().getSection("chat.format.groups");
                if (args.length == 0) {
                    m().send(s, "rankinfo.list", "ranks", String.join(", ", groups.getKeys()), "cmd", getName());
                    return;
                }
                Configuration g = groups.getSection(args[0]);
                if (!groups.contains(args[0])) {
                    m().send(s, "rankinfo.not-found", "rank", args[0]);
                    return;
                }
                m().send(s, "rankinfo.info", "rank", args[0], "display", args[0], "prefix", g.getString("prefix", "-") + "&r",
                        "suffix", g.getString("suffix", "-") + "&r", "weight", g.getString("permission", "-"));
            }

            @Override
            protected List<String> complete(CommandSender s, String[] args) {
                return List.of();
            }
        });
    }

    static Object luckPerms() {
        if (ProxyServer.getInstance().getPluginManager().getPlugin("LuckPerms") == null) return null;
        try {
            return Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reflection-Aufruf über die öffentlichen Interfaces (LuckPerms ist optional). */
    static Object call(Object target, String method, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            Object r = m.invoke(target, args);
            return r instanceof Optional<?> o ? o.orElse(null) : r;
        }
        throw new NoSuchMethodException(method);
    }
}
