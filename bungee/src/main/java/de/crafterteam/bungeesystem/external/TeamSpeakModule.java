package de.crafterteam.bungeesystem.external;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.core.PlayerManager;
import de.crafterteam.bungeesystem.util.Text;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.config.Configuration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * TeamSpeak-3-Bot über ServerQuery:
 * <ul>
 *   <li><b>Supportbot</b> – /tsbot open|close (Minecraft) sowie !open, !close, !notify, !ruhe per Privatnachricht an den Bot</li>
 *   <li><b>Verify</b> – /tsverify: Code an den gleichnamigen TS-Nutzer, /tsverify &lt;Code&gt; bestätigt → Servergruppe +
 *       Beschreibung = Minecraft-Name; /tsverify delete, /tsverify remove &lt;Spieler&gt;</li>
 * </ul>
 */
public final class TeamSpeakModule {

    record Pending(String clientUid, int clientDbId, String code, long expires) {
    }

    private final BungeeSystem plugin;
    private final Ts3Query query;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private ScheduledTask keepAlive;
    private volatile boolean supportOpen;

    public TeamSpeakModule(BungeeSystem plugin) {
        this.plugin = plugin;
        this.query = new Ts3Query(plugin.getLogger());
    }

    private Configuration cfg() {
        return plugin.config().getSection("teamspeak");
    }

    private Messages m() {
        return plugin.messages();
    }

    public void start() {
        plugin.command("tsbot", n -> new BotCmd(plugin, n));
        plugin.command("tsverify", n -> new VerifyCmd(plugin, n));
        ProxyServer.getInstance().getScheduler().runAsync(plugin, this::connect);
        // ServerQuery trennt nach 5 Minuten Leerlauf → alle 4 Minuten "whoami", sonst neu verbinden
        keepAlive = ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            if (!query.connected()) connect();
            else query.exec("whoami");
        }, 4, 4, TimeUnit.MINUTES);
    }

    public void stop() {
        if (keepAlive != null) keepAlive.cancel();
        query.close();
    }

    private synchronized void connect() {
        if (query.connected()) return;
        String host = cfg().getString("host", "localhost");
        try {
            query.connect(host, cfg().getInt("query-port", 10011));
            check(query.exec("login client_login_name=" + Ts3Query.escape(cfg().getString("query-user", "serveradmin"))
                    + " client_login_password=" + Ts3Query.escape(cfg().getString("query-password", ""))), "login");
            check(query.exec("use port=" + cfg().getInt("server-port", 9987)), "use");
            query.exec("clientupdate client_nickname=" + Ts3Query.escape(cfg().getString("nickname", "CrafterTeam-Bot")));
            query.exec("servernotifyregister event=textprivate");
            query.onEvent(this::onEvent);
            plugin.getLogger().info("TeamSpeak verbunden (" + host + ").");
        } catch (Exception e) {
            plugin.getLogger().warning("TeamSpeak-Verbindung fehlgeschlagen: " + e.getMessage());
            query.close();
        }
    }

    private static void check(Ts3Query.Result r, String step) {
        if (!r.ok()) throw new IllegalStateException(step + ": " + r.message() + " (" + r.error() + ")");
    }

    private CompletableFuture<Ts3Query.Result> async(String command) {
        return CompletableFuture.supplyAsync(() -> {
            if (!query.connected()) connect();
            return query.exec(command);
        });
    }

    // ---------------------------------------------------------------- Supportbot

    private boolean setSupport(boolean open) {
        int cid = cfg().getInt("support.channel-id", 0);
        if (cid <= 0) return false;
        String name = cfg().getString(open ? "support.name-open" : "support.name-closed", "");
        String cmd = "channeledit cid=" + cid + (open ? " channel_flag_maxclients_unlimited=1" : " channel_flag_maxclients_unlimited=0 channel_maxclients=0")
                + (name.isBlank() ? "" : " channel_name=" + Ts3Query.escape(name));
        Ts3Query.Result r = query.exec(cmd);
        // 771 = Name unverändert – kein echter Fehler
        boolean ok = r.ok() || r.error() == 771;
        if (ok) supportOpen = open;
        return ok;
    }

    private void onEvent(Map<String, String> ev) {
        if (!"notifytextmessage".equals(ev.get("_event"))) return;
        String msg = ev.getOrDefault("msg", "").trim().toLowerCase();
        String invoker = ev.getOrDefault("invokerid", "");
        String uid = ev.getOrDefault("invokeruid", "");
        if (!msg.startsWith("!") || invoker.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            int dbid = dbId(uid);
            List<Integer> groups = groups(dbid);
            boolean staff = groups.stream().anyMatch(g -> cfg().getIntList("support.staff-groups").contains(g));
            String answer;
            switch (msg) {
                case "!open", "!close" -> {
                    if (!staff) answer = cfg().getString("texts.no-rights", "Dazu hast du keine Rechte.");
                    else if (setSupport(msg.equals("!open"))) {
                        answer = cfg().getString(msg.equals("!open") ? "texts.opened" : "texts.closed", msg);
                        announceSupport(msg.equals("!open"));
                    } else answer = cfg().getString("texts.error", "Fehler.");
                }
                case "!notify" -> answer = toggleGroup(dbid, groups, cfg().getInt("support.notify-group", 0), staff, "Notify");
                case "!ruhe" -> answer = toggleGroup(dbid, groups, cfg().getInt("support.quiet-group", 0), true, "Ruhe");
                default -> answer = cfg().getString("texts.help", "Befehle: !open, !close, !notify, !ruhe");
            }
            query.exec("sendtextmessage targetmode=1 target=" + invoker + " msg=" + Ts3Query.escape(answer));
        });
    }

    private String toggleGroup(int dbid, List<Integer> groups, int group, boolean allowed, String label) {
        if (group <= 0 || dbid <= 0) return cfg().getString("texts.error", "Fehler.");
        if (!allowed) return cfg().getString("texts.no-rights", "Dazu hast du keine Rechte.");
        boolean has = groups.contains(group);
        Ts3Query.Result r = query.exec((has ? "servergroupdelclient" : "servergroupaddclient") + " sgid=" + group + " cldbid=" + dbid);
        if (!r.ok()) return cfg().getString("texts.error", "Fehler.") + " (" + r.message() + ")";
        return label + (has ? " entfernt." : " aktiviert.");
    }

    private void announceSupport(boolean open) {
        for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
            if (p.hasPermission("bungeesystem.tsbot")) m().send(p, open ? "teamspeak.support-opened" : "teamspeak.support-closed");
        }
    }

    private int dbId(String uid) {
        Ts3Query.Result r = query.exec("clientgetdbidfromuid cluid=" + Ts3Query.escape(uid));
        try {
            return Integer.parseInt(r.first().getOrDefault("cldbid", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<Integer> groups(int dbid) {
        if (dbid <= 0) return List.of();
        Ts3Query.Result r = query.exec("servergroupsbyclientid cldbid=" + dbid);
        return r.rows().stream().map(row -> {
            try {
                return Integer.parseInt(row.getOrDefault("sgid", "0"));
            } catch (NumberFormatException e) {
                return 0;
            }
        }).toList();
    }

    final class BotCmd extends Cmd {
        BotCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.tsbot");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            if (!sub.equals("open") && !sub.equals("close")) {
                m().send(s, "teamspeak.bot-usage", "cmd", getName(), "state", m().raw(supportOpen ? "general.on" : "general.off"));
                return;
            }
            boolean open = sub.equals("open");
            CompletableFuture.supplyAsync(() -> {
                if (!query.connected()) connect();
                return setSupport(open);
            }).thenAccept(ok -> {
                if (!ok) m().send(s, "teamspeak.error");
                else announceSupport(open);
            });
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            return args.length == 1 ? filter(args[0], "open", "close") : List.of();
        }
    }

    // ---------------------------------------------------------------- Verify

    final class VerifyCmd extends Cmd {
        VerifyCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("remove")) {
                if (!s.hasPermission("bungeesystem.tsverify.manage")) {
                    m().send(s, "general.no-permission");
                    return;
                }
                plugin.players().lookup(args[1]).thenAccept(opt -> {
                    if (opt.isEmpty()) m().send(s, "general.player-not-found", "player", args[1]);
                    else unverify(opt.get().uuid()).thenAccept(ok -> m().send(s, ok ? "teamspeak.removed-other" : "teamspeak.not-verified", "player", opt.get().name()));
                });
                return;
            }
            ProxiedPlayer p = player(s);
            if (p == null) return;
            if (args.length >= 1 && args[0].equalsIgnoreCase("delete")) {
                unverify(p.getUniqueId()).thenAccept(ok -> m().send(p, ok ? "teamspeak.removed" : "teamspeak.not-verified", "player", p.getName()));
                return;
            }
            if (args.length >= 1) {
                Pending pd = pending.get(p.getUniqueId());
                if (pd == null || pd.expires() < System.currentTimeMillis() || !pd.code().equals(args[0])) {
                    m().send(p, "teamspeak.wrong-code");
                    return;
                }
                pending.remove(p.getUniqueId());
                confirm(p, pd);
                return;
            }
            start(p);
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return filter(args[0], s.hasPermission("bungeesystem.tsverify.manage") ? List.of("delete", "remove") : List.of("delete"));
            if (args.length == 2 && args[0].equalsIgnoreCase("remove")) return players(args[1]);
            return List.of();
        }
    }

    /** Schritt 1: gleichnamigen (und ggf. IP-gleichen) TS-Nutzer suchen und ihm einen Code schicken. */
    private void start(ProxiedPlayer p) {
        String ip = PlayerManager.ip(p);
        boolean sameIp = cfg().getBoolean("verify.require-same-ip", true);
        plugin.db().submit(con -> Database.exists(con, "select 1 from bs_ts_verify where uuid = ?", p.getUniqueId().toString())).thenAccept(already -> {
            if (already) {
                m().send(p, "teamspeak.already");
                return;
            }
            async("clientlist -uid -ip").thenAccept(r -> {
                if (!r.ok()) {
                    m().send(p, "teamspeak.error");
                    return;
                }
                Map<String, String> found = null;
                for (Map<String, String> c : r.rows()) {
                    if (!"0".equals(c.getOrDefault("client_type", "0"))) continue; // Query-Clients ignorieren
                    boolean nameMatch = c.getOrDefault("client_nickname", "").equalsIgnoreCase(p.getName());
                    boolean ipMatch = ip.equals(c.getOrDefault("connection_client_ip", ""));
                    if (nameMatch && (!sameIp || ipMatch)) {
                        found = c;
                        break;
                    }
                }
                if (found == null) {
                    m().send(p, "teamspeak.not-found", "player", p.getName(), "ts", cfg().getString("address", "ts.crafterteam.de"));
                    return;
                }
                String code = Integer.toString(ThreadLocalRandom.current().nextInt(1000, 10000));
                int dbid;
                try {
                    dbid = Integer.parseInt(found.getOrDefault("client_database_id", "0"));
                } catch (NumberFormatException e) {
                    dbid = 0;
                }
                pending.put(p.getUniqueId(), new Pending(found.getOrDefault("client_unique_identifier", ""), dbid, code, System.currentTimeMillis() + 300_000L));
                String text = Text.replace(cfg().getString("texts.code", "Dein Minecraft-Code: {code} – gib ingame /tsverify {code} ein."), "code", code);
                async("sendtextmessage targetmode=1 target=" + found.get("clid") + " msg=" + Ts3Query.escape(text));
                m().send(p, "teamspeak.code-sent", "cmd", plugin.commandName("tsverify"));
            });
        });
    }

    /** Schritt 2: Code bestätigt → Gruppe + Beschreibung + Eintrag in bs_ts_verify. */
    private void confirm(ProxiedPlayer p, Pending pd) {
        int group = cfg().getInt("verify.group", 0);
        String description = Text.replace(cfg().getString("verify.description", "Minecraft: {name}"), "name", p.getName(), "uuid", p.getUniqueId().toString());
        CompletableFuture.supplyAsync(() -> {
            if (!query.connected()) connect();
            if (group > 0) {
                Ts3Query.Result r = query.exec("servergroupaddclient sgid=" + group + " cldbid=" + pd.clientDbId());
                if (!r.ok() && r.error() != 2561) return false; // 2561 = schon in der Gruppe
            }
            query.exec("clientdbedit cldbid=" + pd.clientDbId() + " client_description=" + Ts3Query.escape(description));
            return true;
        }).thenAccept(ok -> {
            if (!ok) {
                m().send(p, "teamspeak.error");
                return;
            }
            plugin.db().run(con -> Database.exec(con, "insert into bs_ts_verify (uuid, ts_uid, created) values (?, ?, ?) "
                    + "on duplicate key update ts_uid = values(ts_uid), created = values(created)", p.getUniqueId().toString(), pd.clientUid(), System.currentTimeMillis()));
            m().send(p, "teamspeak.verified");
        });
    }

    private CompletableFuture<Boolean> unverify(UUID uuid) {
        return plugin.db().submit(con -> Database.string(con, "select ts_uid from bs_ts_verify where uuid = ?", uuid.toString())).thenCompose(uid -> {
            if (uid == null) return CompletableFuture.completedFuture(false);
            return CompletableFuture.supplyAsync(() -> {
                if (!query.connected()) connect();
                int dbid = dbId(uid);
                int group = cfg().getInt("verify.group", 0);
                if (dbid > 0 && group > 0) query.exec("servergroupdelclient sgid=" + group + " cldbid=" + dbid);
                if (dbid > 0) query.exec("clientdbedit cldbid=" + dbid + " client_description=");
                plugin.db().run(con -> Database.exec(con, "delete from bs_ts_verify where uuid = ?", uuid.toString()));
                return true;
            });
        });
    }
}
