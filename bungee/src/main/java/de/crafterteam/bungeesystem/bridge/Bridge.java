package de.crafterteam.bungeesystem.bridge;

import de.crafterteam.bungeesystem.BungeeSystem;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plugin-Messaging zwischen Proxy und Paper-Servern (LobbySystem, MissileWars, BungeeSystem-Addon).
 * Kanal: {@value #CHANNEL}. Jede Nachricht: writeUTF(typ) + Felder.
 *
 * <pre>
 * Proxy → Server:  HELLO(version, module) · ONLINETIME(uuid, ms) · CLANTAG(uuid, tag) · FRIEND_SETTINGS(uuid, settings)
 *                  FRIEND_REFRESH(uuid) · COINS_TAKE(id, uuid, betrag) · GUI_REPORTS(…) · GUI_PUNISH(…)
 * Server → Proxy:  RUN(uuid, befehl) · COINS_RESULT(id, ok) · ADDON(version)
 * </pre>
 * Nachrichten von Clients auf diesem Kanal werden verworfen.
 */
public final class Bridge implements Listener {

    public static final String CHANNEL = "crafterteam:bungee";
    public static final int VERSION = 1;

    private final BungeeSystem plugin;
    private final Map<Integer, CompletableFuture<Boolean>> coinRequests = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Long>> valueRequests = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    /** Server, auf denen das Addon (GUIs) läuft. */
    private final Set<String> addonServers = ConcurrentHashMap.newKeySet();
    /** Befehle, die ein Server im Namen eines Spielers ausführen darf (z. B. Klick in einer GUI). */
    private final Set<String> allowed = ConcurrentHashMap.newKeySet();

    public Bridge(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    public void allow(java.util.Collection<String> commands) {
        for (String c : commands) allowed.add(c.toLowerCase());
    }

    public boolean hasAddon(ProxiedPlayer p) {
        return p.getServer() != null && addonServers.contains(p.getServer().getInfo().getName());
    }

    // ---------------------------------------------------------------- Senden

    @FunctionalInterface
    public interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    public static byte[] build(String type, Writer w) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(type);
            if (w != null) w.write(out);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An den Server des Spielers (queue=true: wird zugestellt, sobald dort jemand online ist). */
    public void send(ProxiedPlayer p, String type, Writer w) {
        if (p == null || p.getServer() == null) return;
        p.getServer().sendData(CHANNEL, build(type, w));
    }

    public void send(ServerInfo server, String type, Writer w) {
        if (server == null || server.getPlayers().isEmpty()) return;
        server.sendData(CHANNEL, build(type, w), false);
    }

    /** An alle Server mit Spielern. */
    public void broadcast(String type, Writer w) {
        byte[] data = build(type, w);
        for (ServerInfo s : ProxyServer.getInstance().getServers().values()) {
            if (!s.getPlayers().isEmpty()) s.sendData(CHANNEL, data, false);
        }
    }

    public void hello(ProxiedPlayer p) {
        String modules = plugin.modules();
        StringBuilder cmds = new StringBuilder();
        for (String key : new String[]{"friend", "party", "clan", "report", "onlinetime"}) {
            cmds.append(cmds.isEmpty() ? "" : ";").append(key).append('=').append(plugin.commandName(key));
        }
        String commands = cmds.toString();
        send(p, "HELLO", out -> {
            out.writeInt(VERSION);
            out.writeUTF(modules);
            out.writeUTF(commands);
        });
    }

    public void onlineTime(ProxiedPlayer p, long ms) {
        send(p, "ONLINETIME", out -> {
            out.writeUTF(p.getUniqueId().toString());
            out.writeLong(ms);
        });
    }

    public void clanTag(UUID uuid, String tag) {
        broadcast("CLANTAG", out -> {
            out.writeUTF(uuid.toString());
            out.writeUTF(tag == null ? "" : tag);
        });
    }

    public void friendRefresh(UUID uuid) {
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
        if (p != null) send(p, "FRIEND_REFRESH", out -> out.writeUTF(uuid.toString()));
    }

    public void friendSettings(UUID uuid, String settings) {
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
        if (p != null) send(p, "FRIEND_SETTINGS", out -> {
            out.writeUTF(uuid.toString());
            out.writeUTF(settings);
        });
    }

    /**
     * Coins abziehen. Zuerst fragt der Proxy den Server des Spielers (LobbySystem/MissileWars halten das Guthaben im Cache);
     * antwortet keiner, wird direkt in ls_players abgebucht.
     */
    public CompletableFuture<Boolean> takeCoins(UUID uuid, long amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(true);
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
        if (p == null || p.getServer() == null) return takeCoinsDb(uuid, amount);
        int id = ids.incrementAndGet();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        coinRequests.put(id, answer);
        send(p, "COINS_TAKE", out -> {
            out.writeInt(id);
            out.writeUTF(uuid.toString());
            out.writeLong(amount);
        });
        return answer.completeOnTimeout(null, 3, TimeUnit.SECONDS).thenCompose(ok -> {
            coinRequests.remove(id);
            return ok == null ? takeCoinsDb(uuid, amount) : CompletableFuture.completedFuture(ok);
        });
    }

    /** Coins gutschreiben (über den Server des Spielers, sonst direkt in der DB). */
    public CompletableFuture<Boolean> addCoins(UUID uuid, long amount) {
        if (amount <= 0) return takeCoins(uuid, -amount);
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
        if (p == null || p.getServer() == null) return addCoinsDb(uuid, amount);
        int id = ids.incrementAndGet();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        coinRequests.put(id, answer);
        send(p, "COINS_ADD", out -> {
            out.writeInt(id);
            out.writeUTF(uuid.toString());
            out.writeLong(amount);
        });
        return answer.completeOnTimeout(null, 3, TimeUnit.SECONDS).thenCompose(ok -> {
            coinRequests.remove(id);
            return ok == null ? addCoinsDb(uuid, amount) : CompletableFuture.completedFuture(ok);
        });
    }

    private CompletableFuture<Boolean> addCoinsDb(UUID uuid, long amount) {
        return plugin.db().submit(con -> de.crafterteam.bungeesystem.core.Database.exec(con,
                "update ls_players set coins = coins + ? where uuid = ?", amount, uuid.toString()) > 0);
    }

    /** Guthaben lesen (DB-Stand; Server-Caches können kurzzeitig abweichen). */
    public CompletableFuture<Long> coins(UUID uuid) {
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
        if (p != null && p.getServer() != null) {
            int id = ids.incrementAndGet();
            CompletableFuture<Long> answer = new CompletableFuture<>();
            valueRequests.put(id, answer);
            send(p, "COINS_GET", out -> {
                out.writeInt(id);
                out.writeUTF(uuid.toString());
            });
            return answer.completeOnTimeout(null, 2, TimeUnit.SECONDS).thenCompose(v -> {
                valueRequests.remove(id);
                return v != null && v >= 0 ? CompletableFuture.completedFuture(v) : coinsDb(uuid);
            });
        }
        return coinsDb(uuid);
    }

    private CompletableFuture<Long> coinsDb(UUID uuid) {
        return plugin.db().submit(con -> de.crafterteam.bungeesystem.core.Database.longValue(con,
                "select coins from ls_players where uuid = ?", -1, uuid.toString()));
    }

    private CompletableFuture<Boolean> takeCoinsDb(UUID uuid, long amount) {
        return plugin.db().submit(con -> de.crafterteam.bungeesystem.core.Database.exec(con,
                "update ls_players set coins = coins - ? where uuid = ? and coins >= ?", amount, uuid.toString(), amount) > 0);
    }

    // ---------------------------------------------------------------- Empfangen

    @EventHandler
    public void onMessage(PluginMessageEvent e) {
        if (!CHANNEL.equals(e.getTag())) return;
        e.setCancelled(true); // nie an den Client / anderen Server weiterreichen
        if (!(e.getSender() instanceof Server server)) return; // gefälschte Client-Nachrichten ignorieren
        if (!(e.getReceiver() instanceof ProxiedPlayer player)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(e.getData()))) {
            String type = in.readUTF();
            switch (type) {
                case "ADDON" -> addonServers.add(server.getInfo().getName());
                case "RUN" -> {
                    UUID uuid = UUID.fromString(in.readUTF());
                    String line = in.readUTF().trim();
                    if (line.startsWith("/")) line = line.substring(1);
                    // nur im Namen des Spielers, über dessen Verbindung die Nachricht kam
                    if (!uuid.equals(player.getUniqueId())) return;
                    ProxiedPlayer target = player;
                    String cmd = line.split(" ", 2)[0].toLowerCase();
                    if (!allowed.contains(cmd)) {
                        plugin.getLogger().warning("Server " + server.getInfo().getName() + " wollte nicht erlaubten Befehl ausführen: /" + cmd);
                        return;
                    }
                    String run = line;
                    ProxyServer.getInstance().getScheduler().runAsync(plugin,
                            () -> ProxyServer.getInstance().getPluginManager().dispatchCommand(target, run));
                }
                case "COINS_VALUE" -> {
                    int id = in.readInt();
                    long v = in.readLong();
                    CompletableFuture<Long> f = valueRequests.remove(id);
                    if (f != null) f.complete(v);
                }
                case "COINS_RESULT" -> {
                    int id = in.readInt();
                    boolean ok = in.readBoolean();
                    CompletableFuture<Boolean> f = coinRequests.remove(id);
                    if (f != null) f.complete(ok);
                }
                default -> {
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            plugin.getLogger().warning("Ungültige Plugin-Nachricht von " + server.getInfo().getName() + ": " + ex.getMessage());
        }
    }

    public void serverEmpty(String name) {
        addonServers.remove(name);
    }
}
