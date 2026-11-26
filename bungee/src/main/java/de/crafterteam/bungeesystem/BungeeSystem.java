package de.crafterteam.bungeesystem;

import de.crafterteam.bungeesystem.bridge.Bridge;
import de.crafterteam.bungeesystem.bridge.Guis;
import de.crafterteam.bungeesystem.chat.ChatModule;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.ConnectionListener;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.core.PlayerManager;
import de.crafterteam.bungeesystem.core.YamlFile;
import de.crafterteam.bungeesystem.core.Settings;
import de.crafterteam.bungeesystem.community.CommunityTools;
import de.crafterteam.bungeesystem.community.PollModule;
import de.crafterteam.bungeesystem.external.DiscordModule;
import de.crafterteam.bungeesystem.external.TeamSpeakModule;
import de.crafterteam.bungeesystem.network.NetworkModule;
import de.crafterteam.bungeesystem.team.SupportModule;
import de.crafterteam.bungeesystem.team.TeamTools;
import de.crafterteam.bungeesystem.team.WarnModule;
import de.crafterteam.bungeesystem.notify.NotifyManager;
import de.crafterteam.bungeesystem.onlinetime.OnlineTimeCommands;
import de.crafterteam.bungeesystem.punish.PunishCommands;
import de.crafterteam.bungeesystem.punish.PunishManager;
import de.crafterteam.bungeesystem.report.ReportManager;
import de.crafterteam.bungeesystem.social.ClanManager;
import de.crafterteam.bungeesystem.social.FriendManager;
import de.crafterteam.bungeesystem.social.PartyManager;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * BungeeSystem – Ban/Mute/Report, Freunde/Party/Clan, Onlinezeit, Chat & CMDBlock für BungeeCord.
 * Arbeitet mit derselben MySQL-Datenbank wie LobbySystem & MissileWars.
 */
public final class BungeeSystem extends Plugin {

    /** Standard-Befehlsnamen (überschreibbar in config.yml → commands). */
    private static final Map<String, List<String>> DEFAULT_COMMANDS = new HashMap<>();

    static {
        DEFAULT_COMMANDS.put("ban", List.of("ban", "tempban", "tban"));
        DEFAULT_COMMANDS.put("mute", List.of("mute", "tempmute"));
        DEFAULT_COMMANDS.put("unban", List.of("unban", "pardon"));
        DEFAULT_COMMANDS.put("unmute", List.of("unmute"));
        DEFAULT_COMMANDS.put("kick", List.of("kick"));
        DEFAULT_COMMANDS.put("check", List.of("check", "baninfo"));
        DEFAULT_COMMANDS.put("history", List.of("history", "strafen"));
        DEFAULT_COMMANDS.put("punish", List.of("punish", "bansystem", "bs"));
        DEFAULT_COMMANDS.put("banreasons", List.of("banreasons", "gründe"));
        DEFAULT_COMMANDS.put("report", List.of("report", "melden"));
        DEFAULT_COMMANDS.put("reports", List.of("reports"));
        DEFAULT_COMMANDS.put("notify", List.of("notify"));
        DEFAULT_COMMANDS.put("friend", List.of("friend", "f", "freunde", "friends"));
        DEFAULT_COMMANDS.put("msg", List.of("msg", "tell", "w", "whisper"));
        DEFAULT_COMMANDS.put("reply", List.of("r", "reply"));
        DEFAULT_COMMANDS.put("party", List.of("party", "p"));
        DEFAULT_COMMANDS.put("partychat", List.of("pc", "partychat"));
        DEFAULT_COMMANDS.put("clan", List.of("clan", "clans"));
        DEFAULT_COMMANDS.put("clanchat", List.of("cc", "clanchat"));
        DEFAULT_COMMANDS.put("onlinetime", List.of("onlinetime", "onlinezeit", "ot", "playtime"));
        DEFAULT_COMMANDS.put("globalmute", List.of("globalmute", "gmute"));
        DEFAULT_COMMANDS.put("chatclear", List.of("chatclear", "cc-all", "clearchat"));
        DEFAULT_COMMANDS.put("chat", List.of("chat", "togglechat"));
        DEFAULT_COMMANDS.put("teamchat", List.of("teamchat", "tc"));
        DEFAULT_COMMANDS.put("adminchat", List.of("adminchat", "ac"));
        DEFAULT_COMMANDS.put("teamonline", List.of("teamonline", "teaminfo"));
        DEFAULT_COMMANDS.put("jointo", List.of("jointo", "goto"));
        DEFAULT_COMMANDS.put("whereis", List.of("whereis", "find"));
        DEFAULT_COMMANDS.put("staffstats", List.of("staff", "staffstats"));
        DEFAULT_COMMANDS.put("warn", List.of("warn", "warns"));
        DEFAULT_COMMANDS.put("exemption", List.of("exemption", "ea"));
        DEFAULT_COMMANDS.put("support", List.of("support"));
        DEFAULT_COMMANDS.put("bugreport", List.of("bugreport", "bug"));
        DEFAULT_COMMANDS.put("maintenance", List.of("maintenance", "wartung"));
        DEFAULT_COMMANDS.put("motd", List.of("motd"));
        DEFAULT_COMMANDS.put("hub", List.of("hub", "lobby", "l"));
        DEFAULT_COMMANDS.put("broadcast", List.of("broadcast", "bc"));
        DEFAULT_COMMANDS.put("help", List.of("help", "hilfe"));
        DEFAULT_COMMANDS.put("ping", List.of("ping"));
        DEFAULT_COMMANDS.put("online", List.of("online"));
        DEFAULT_COMMANDS.put("whereami", List.of("whereami"));
        DEFAULT_COMMANDS.put("debugreset", List.of("debugreset"));
        DEFAULT_COMMANDS.put("poll", List.of("poll", "umfrage"));
        DEFAULT_COMMANDS.put("joinme", List.of("joinme"));
        DEFAULT_COMMANDS.put("advert", List.of("advert", "geworben"));
        DEFAULT_COMMANDS.put("coins", List.of("coins"));
        DEFAULT_COMMANDS.put("rankinfo", List.of("rankinfo"));
        DEFAULT_COMMANDS.put("tsbot", List.of("tsbot"));
        DEFAULT_COMMANDS.put("tsverify", List.of("tsverify"));
        DEFAULT_COMMANDS.put("admin", List.of("bungeesystem", "bsys"));
    }

    private YamlFile configFile, messagesFile;
    private Messages messages;
    private Database db;
    private PlayerManager players;
    private Bridge bridge;
    private Guis guis;
    private NotifyManager notifier;
    private PunishManager punish;
    private ReportManager reports;
    private FriendManager friends;
    private PartyManager parties;
    private ClanManager clans;
    private ChatModule chat;
    private Settings settings;
    private SupportModule support;
    private NetworkModule network;
    private PollModule poll;
    private DiscordModule discord;
    private TeamSpeakModule teamspeak;
    private final Map<String, String> commandNames = new ConcurrentHashMap<>();
    /** Module, die beim Start geladen wurden bzw. per /bungeesystem modules disable kurz aus sind. */
    private final Set<String> loadedModules = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedModules = ConcurrentHashMap.newKeySet();
    private volatile String currentModule;
    private final Set<String> ownCommands = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        configFile = new YamlFile(this, "config.yml");
        messagesFile = new YamlFile(this, "messages.yml");
        configFile.load();
        messagesFile.load();
        messages = new Messages(messagesFile);

        db = new Database(this, config().getSection("mysql"));
        try {
            db.createTables();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Keine Verbindung zur MySQL-Datenbank – bitte plugins/BungeeSystem/config.yml prüfen! "
                    + "Das BungeeSystem bleibt deaktiviert.", e);
            return;
        }
        getLogger().info("MySQL verbunden.");

        players = new PlayerManager(this);
        bridge = new Bridge(this);
        guis = new Guis(this);
        notifier = new NotifyManager(this);
        ProxyServer.getInstance().registerChannel(Bridge.CHANNEL);
        var pm = getProxy().getPluginManager();
        pm.registerListener(this, bridge);
        pm.registerListener(this, new ConnectionListener(this));

        settings = new Settings(this);
        settings.load();

        punish = new PunishManager(this); // auch ohne Modul für Mute-Prüfungen im Chat (dann leer)
        load("punish", () -> {
            punish.loadReasons();
            punish.reload().join();
            PunishCommands.register(this);
            getLogger().info("Ban-/Mute-Modul: " + punish.allReasons().size() + " Vorlagen.");
        });
        load("report", () -> {
            reports = new ReportManager(this);
            ReportManager.register(this);
        });
        load("notify", () -> command("notify", n -> new NotifyCmd(this, n)));
        load("friends", () -> {
            friends = new FriendManager(this);
            FriendManager.register(this);
        });
        load("party", () -> {
            parties = new PartyManager(this);
            PartyManager.register(this);
        });
        load("clan", () -> {
            clans = new ClanManager(this);
            ClanManager.register(this);
        });
        load("onlinetime", () -> OnlineTimeCommands.register(this));
        if (module("chat") || module("cmdblock")) {
            chat = new ChatModule(this);
            pm.registerListener(this, chat);
            load("chat", () -> ChatModule.register(this));
        }
        // ---- Team-Tools
        load("teamchat", () -> TeamTools.registerTeamChat(this));
        load("adminchat", () -> TeamTools.registerAdminChat(this));
        load("teamonline", () -> TeamTools.registerTeamOnline(this));
        load("jointo", () -> TeamTools.registerJoinTo(this));
        load("whereis", () -> TeamTools.registerWhereIs(this));
        load("staffstats", () -> TeamTools.registerStaffStats(this));
        load("warn", () -> WarnModule.registerWarn(this));
        load("exemption", () -> WarnModule.registerExemption(this));
        load("support", () -> {
            support = new SupportModule(this);
            support.registerSupport();
        });
        load("bugreport", () -> SupportModule.registerBugReport(this));
        // ---- Netzwerk
        network = new NetworkModule(this);
        pm.registerListener(this, network);
        load("maintenance", network::registerMaintenance);
        load("motd", network::registerMotd);
        load("hub", network::registerHub);
        load("servers", network::registerServers);
        load("broadcast", network::registerBroadcast);
        load("autobroadcast", network::startAutoBroadcast);
        load("help", network::registerHelp);
        load("links", network::registerLinks);
        load("ping", network::registerPing);
        load("online", network::registerOnline);
        load("whereami", network::registerWhereAmI);
        load("debug", network::registerDebug);
        // ---- Community
        load("poll", () -> {
            poll = new PollModule(this);
            poll.register();
        });
        load("joinme", () -> CommunityTools.registerJoinMe(this));
        load("advert", () -> CommunityTools.registerAdvert(this));
        load("coins", () -> CommunityTools.registerCoins(this));
        load("rankinfo", () -> CommunityTools.registerRankInfo(this));
        // ---- Extern
        load("discord", () -> {
            discord = new DiscordModule(this);
            discord.start();
        });
        load("teamspeak", () -> {
            teamspeak = new TeamSpeakModule(this);
            teamspeak.start();
        });
        currentModule = null;
        command("admin", n -> new AdminCmd(this, n));
        bridge.allow(ownCommands);

        startTasks();
        // Reload-sicher: bereits verbundene Spieler übernehmen
        for (ProxiedPlayer p : getProxy().getPlayers()) {
            players.join(p);
            if (clans != null) clans.refresh(p.getUniqueId());
        }
        getLogger().info("BungeeSystem v" + getDescription().getVersion() + " gestartet – Module: " + modules());
    }

    private void startTasks() {
        var s = getProxy().getScheduler();
        s.schedule(this, () -> {
            if (punish != null && loadedModules.contains("punish")) punish.reload();
            settings.load();
        }, 60, 60, TimeUnit.SECONDS);
        s.schedule(this, players::flushAll, 5, 5, TimeUnit.MINUTES);
        if (module("onlinetime")) s.schedule(this, () -> OnlineTimeCommands.push(this), 60, 60, TimeUnit.SECONDS);
        if (parties != null) s.schedule(this, parties::cleanup, 2, 5, TimeUnit.MINUTES);
        if (config().getBoolean("sync-server-status", true)) s.schedule(this, this::syncServers, 5, 10, TimeUnit.SECONDS);
    }

    /** Spielerzahlen aller Server mit Spielern in ls_servers – so sehen Freunde in der Lobby jeden Server als online. */
    private void syncServers() {
        List<Object[]> rows = new ArrayList<>();
        for (ServerInfo s : getProxy().getServers().values()) {
            if (!s.getPlayers().isEmpty()) rows.add(new Object[]{s.getName(), s.getPlayers().size()});
        }
        if (rows.isEmpty()) return;
        long now = System.currentTimeMillis();
        db.run(con -> {
            try (PreparedStatement ps = con.prepareStatement("insert into ls_servers (name, online, max_players, updated) values (?, ?, 0, ?) "
                    + "on duplicate key update online = values(online), updated = values(updated)")) {
                for (Object[] r : rows) {
                    ps.setString(1, (String) r[0]);
                    ps.setInt(2, (Integer) r[1]);
                    ps.setLong(3, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public void onDisable() {
        if (teamspeak != null) teamspeak.stop();
        if (discord != null) discord.stop();
        if (players != null) {
            players.flushAll();
        }
        if (db != null) db.shutdown();
    }

    public void reload() {
        configFile.load();
        messagesFile.load();
        if (punish != null) punish.loadReasons();
    }

    // ---------------------------------------------------------------- Module

    /** Alle Module in Anzeige-Reihenfolge. */
    public static final List<String> MODULES = List.of("punish", "report", "notify", "friends", "party", "clan", "onlinetime", "chat", "cmdblock",
            "teamchat", "adminchat", "teamonline", "jointo", "whereis", "staffstats", "warn", "exemption", "support", "bugreport",
            "maintenance", "motd", "hub", "servers", "broadcast", "autobroadcast", "help", "links", "ping", "online", "whereami", "debug",
            "poll", "joinme", "advert", "coins", "rankinfo", "discord", "teamspeak");

    private void load(String module, Runnable init) {
        if (!module(module)) return;
        currentModule = module;
        try {
            init.run();
            loadedModules.add(module);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Modul " + module + " konnte nicht geladen werden", e);
        } finally {
            currentModule = null;
        }
    }

    public String currentModule() {
        return currentModule;
    }

    /** Modul geladen und nicht pausiert. */
    public boolean moduleActive(String module) {
        return loadedModules.contains(module) && !pausedModules.contains(module) || module.equals("cmdblock") && module("cmdblock") && !pausedModules.contains(module);
    }

    public Set<String> loadedModules() {
        return loadedModules;
    }

    public Set<String> pausedModules() {
        return pausedModules;
    }

    // ---------------------------------------------------------------- Befehle

    /** Befehl mit Namen aus config.yml → commands.&lt;key&gt; registrieren (leere Liste = deaktiviert). */
    public void command(String key, Function<String[], Cmd> factory) {
        List<String> names = config().contains("commands." + key) ? config().getStringList("commands." + key) : DEFAULT_COMMANDS.get(key);
        if (names == null || names.isEmpty()) return;
        String[] arr = names.stream().map(n -> n.toLowerCase(Locale.ROOT).replaceFirst("^/", "")).toArray(String[]::new);
        commandNames.put(key, arr[0]);
        for (String n : arr) ownCommands.add(n);
        getProxy().getPluginManager().registerCommand(this, factory.apply(arr));
    }

    /** Befehl mit fest vorgegebenen Namen (z. B. aus einer Liste in der config) registrieren. */
    public void command(String key, List<String> names, Function<String[], Cmd> factory) {
        if (names == null || names.isEmpty()) return;
        String[] arr = names.stream().map(n -> n.toLowerCase(Locale.ROOT).replaceFirst("^/", "")).toArray(String[]::new);
        commandNames.putIfAbsent(key, arr[0]);
        for (String n : arr) ownCommands.add(n);
        getProxy().getPluginManager().registerCommand(this, factory.apply(arr));
    }

    public String commandName(String key) {
        String n = commandNames.get(key);
        if (n != null) return n;
        List<String> d = DEFAULT_COMMANDS.get(key);
        return d == null ? key : d.get(0);
    }

    public boolean isOwnCommand(String label) {
        return ownCommands.contains(label.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- Helfer

    public boolean module(String name) {
        return config().getBoolean("modules." + name, !name.equals("discord") && !name.equals("teamspeak"));
    }

    public String modules() {
        List<String> on = new ArrayList<>();
        for (String m : MODULES) if (moduleActive(m)) on.add(m);
        return String.join(",", on);
    }

    /** Servername passt auf eines der Muster ("MissileWars*", "*-Event", "Lobby-1"). */
    public boolean matches(String server, List<String> patterns) {
        if (server == null) return false;
        String s = server.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            String x = p.toLowerCase(Locale.ROOT);
            if (x.equals("*")) return true;
            if (x.startsWith("*") && x.endsWith("*") && x.length() > 2 && s.contains(x.substring(1, x.length() - 1))) return true;
            if (x.endsWith("*") && s.startsWith(x.substring(0, x.length() - 1))) return true;
            if (x.startsWith("*") && s.endsWith(x.substring(1))) return true;
            if (s.equals(x)) return true;
        }
        return false;
    }

    public Configuration config() {
        return configFile.get();
    }

    public Messages messages() {
        return messages;
    }

    public Database db() {
        return db;
    }

    public PlayerManager players() {
        return players;
    }

    public Bridge bridge() {
        return bridge;
    }

    public Guis guis() {
        return guis;
    }

    public NotifyManager notifier() {
        return notifier;
    }

    public PunishManager punish() {
        return punish;
    }

    public ReportManager reports() {
        return reports;
    }

    public FriendManager friends() {
        return friends;
    }

    public PartyManager parties() {
        return parties;
    }

    public ClanManager clans() {
        return clans;
    }

    public ChatModule chat() {
        return chat;
    }

    public Settings settings() {
        return settings;
    }

    public SupportModule support() {
        return support;
    }

    public NetworkModule network() {
        return network;
    }

    public PollModule poll() {
        return poll;
    }

    public DiscordModule discord() {
        return discord;
    }

    public TeamSpeakModule teamspeak() {
        return teamspeak;
    }

    public YamlFile configFile() {
        return configFile;
    }

    // ================================================================ /notify & /bungeesystem

    static final class NotifyCmd extends Cmd {
        NotifyCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.notify");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            if (args.length == 0) {
                m().send(p, "notify.header");
                for (String t : NotifyManager.TYPES) {
                    if (!p.hasPermission("bungeesystem.notify." + t) && !p.hasPermission("bungeesystem.notify.*")) continue;
                    m().send(p, "notify.entry", "type", t, "state", m().raw(plugin.notifier().receives(p, t) ? "general.on" : "general.off"),
                            "cmd", getName());
                }
                return;
            }
            String type = args[0].toLowerCase(Locale.ROOT);
            if (!type.equals("all") && !NotifyManager.TYPES.contains(type)) {
                m().send(p, "notify.usage", "cmd", getName(), "types", String.join(", ", NotifyManager.TYPES));
                return;
            }
            plugin.notifier().toggle(p, type);
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length != 1) return List.of();
            List<String> out = new ArrayList<>(NotifyManager.TYPES);
            out.add("all");
            return filter(args[0], out);
        }
    }

    static final class AdminCmd extends Cmd {
        AdminCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.admin");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                plugin.reload();
                m().send(s, "general.reloaded");
                return;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("modules")) {
                String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
                String mod = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
                switch (sub) {
                    case "enable" -> {
                        if (mod == null || !plugin.loadedModules.contains(mod)) {
                            m().send(s, "general.module-not-loaded", "module", String.valueOf(mod));
                            return;
                        }
                        plugin.pausedModules.remove(mod);
                        m().send(s, "general.module-enabled", "module", mod);
                    }
                    case "disable" -> {
                        if (mod == null || !MODULES.contains(mod)) {
                            m().send(s, "general.module-not-loaded", "module", String.valueOf(mod));
                            return;
                        }
                        plugin.pausedModules.add(mod);
                        m().send(s, "general.module-disabled-temp", "module", mod);
                    }
                    case "reload" -> {
                        plugin.reload();
                        m().send(s, "general.reloaded");
                    }
                    default -> {
                        m().send(s, "general.modules-header", "count", Integer.toString(plugin.loadedModules.size()));
                        for (String x : MODULES) {
                            String state = plugin.pausedModules.contains(x) ? "general.module-state-paused"
                                    : plugin.moduleActive(x) ? "general.module-state-on" : "general.module-state-off";
                            m().send(s, "general.modules-entry", "module", x, "state", m().raw(state), "cmd", getName());
                        }
                    }
                }
                return;
            }
            m().send(s, "general.info", "version", plugin.getDescription().getVersion(), "modules", plugin.modules(),
                    "players", Integer.toString(ProxyServer.getInstance().getOnlineCount()));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return filter(args[0], "reload", "info", "modules");
            if (args.length == 2 && args[0].equalsIgnoreCase("modules")) return filter(args[1], "list", "enable", "disable", "reload");
            if (args.length == 3 && args[0].equalsIgnoreCase("modules")) return filter(args[2], MODULES);
            return List.of();
        }
    }
}
