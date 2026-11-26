package de.crafterteam.bungeesystem.chat;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.core.PlayerManager;
import de.crafterteam.bungeesystem.punish.PunishManager;
import de.crafterteam.bungeesystem.util.Text;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Chat-Modul: Mute-Sperre, Globalmute, Anti-Spam (Cooldown + Ähnlichkeit in %), Filter (IPs, Domains, Wörter),
 * Caps-Sperre, @Erwähnungen, frei einstellbares Chat-Format mit Prefix (LuckPerms oder config), Chat leeren,
 * persönlicher Chat-Schalter und CMDBlock.
 */
public final class ChatModule implements Listener {

    private static final Pattern IP = Pattern.compile("\\b(\\d{1,3}[.,\\s]\\s?){3}\\d{1,3}(:\\d{2,5})?\\b");
    private static final Pattern DOMAIN = Pattern.compile("(?i)\\b([a-z0-9-]+\\s?(\\.|\\(dot\\)|\\[dot]|\\spunkt\\s)\\s?)+(de|com|net|org|eu|io|me|tv|gg|at|ch|xyz|info|biz|co|cc|to|ru|nl|fr|uk|us|online|shop|club|site|dev|app|link|ly)\\b");

    private final BungeeSystem plugin;
    private final Map<UUID, Deque<String>> history = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();
    private volatile boolean globalMute;
    private final Set<String> mutedServers = ConcurrentHashMap.newKeySet();
    private Object luckPerms;
    private boolean luckPermsChecked;

    public ChatModule(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Configuration cfg() {
        return plugin.config().getSection("chat");
    }

    private Messages m() {
        return plugin.messages();
    }

    public void forget(UUID uuid) {
        history.remove(uuid);
        lastMessage.remove(uuid);
    }

    // ================================================================ Chat-Event

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(ChatEvent e) {
        if (e.isCancelled() || !(e.getSender() instanceof ProxiedPlayer p)) return;
        String msg = e.getMessage();
        if (e.isCommand()) {
            onCommand(e, p, msg);
            return;
        }
        PunishManager.Punishment mute = plugin.punish().mute(p.getUniqueId());
        if (mute != null) {
            e.setCancelled(true);
            Messages.sendRaw(p, plugin.punish().muteMessage(mute));
            return;
        }
        if (!cfg().getBoolean("enabled", true) || !plugin.moduleActive("chat")) return;
        String server = p.getServer() == null ? "" : p.getServer().getInfo().getName();
        if ((globalMute || mutedServers.contains(server.toLowerCase())) && !p.hasPermission("bungeesystem.chat.globalmute.bypass")) {
            e.setCancelled(true);
            m().send(p, "chat.globalmute-active");
            return;
        }
        boolean formatting = formats(server);
        String checked = check(p, msg, formatting);
        if (checked == null) {
            e.setCancelled(true);
            return;
        }
        if (formatting) {
            e.setCancelled(true);
            deliver(p, checked, server);
        } else {
            pingMentions(p, checked, p.getServer() == null ? null : p.getServer().getInfo());
        }
    }

    /**
     * Spam-, Caps- & Filter-Prüfung. Rückgabe: (ggf. bereinigte) Nachricht oder null = blockiert.
     * Ändern geht nur, wenn der Proxy formatiert (signierte Nachrichten lassen sich sonst nur abbrechen).
     */
    private String check(ProxiedPlayer p, String msg, boolean canModify) {
        boolean bypass = p.hasPermission("bungeesystem.chat.bypass");
        Configuration spam = cfg().getSection("anti-spam");
        if (!bypass && spam.getBoolean("enabled", true)) {
            long now = System.currentTimeMillis();
            long cd = spam.getLong("cooldown-ms", 1500);
            Long last = lastMessage.get(p.getUniqueId());
            if (last != null && last + cd > now) {
                m().send(p, "chat.cooldown", "seconds", String.format(Locale.GERMANY, "%.1f", (last + cd - now) / 1000.0));
                return null;
            }
            int similarity = spam.getInt("similarity", 85);
            Deque<String> h = history.computeIfAbsent(p.getUniqueId(), u -> new ArrayDeque<>());
            synchronized (h) {
                for (String old : h) {
                    if (similarity > 0 && msg.length() >= spam.getInt("min-length", 4) && Text.similarity(old, msg) >= similarity) {
                        m().send(p, "chat.similar", "percent", Integer.toString(similarity));
                        return null;
                    }
                }
                h.addFirst(msg);
                while (h.size() > Math.max(1, spam.getInt("compare-last", 3))) h.removeLast();
            }
            lastMessage.put(p.getUniqueId(), now);
        }
        Configuration caps = cfg().getSection("caps");
        if (!bypass && caps.getBoolean("enabled", true) && msg.length() >= caps.getInt("min-length", 6)) {
            long upper = msg.chars().filter(Character::isUpperCase).count();
            long letters = msg.chars().filter(Character::isLetter).count();
            if (letters > 0 && upper * 100 / letters >= caps.getInt("max-percent", 70)) {
                if (canModify) msg = msg.toLowerCase(Locale.ROOT);
                else {
                    m().send(p, "chat.caps");
                    return null;
                }
            }
        }
        if (!p.hasPermission("bungeesystem.chat.filter.bypass")) {
            String filtered = filter(p, msg, canModify);
            if (filtered == null) return null;
            msg = filtered;
        }
        return msg;
    }

    /** Filter für IPs, Domains & gesperrte Wörter. null = blockiert. */
    private String filter(ProxiedPlayer p, String msg, boolean canModify) {
        Configuration f = cfg().getSection("filter");
        if (!f.getBoolean("enabled", true)) return msg;
        String lower = msg.toLowerCase(Locale.ROOT);
        if (f.getBoolean("block-ips", true) && IP.matcher(msg).find()) return blocked(p, msg, "IP");
        if (f.getBoolean("block-domains", true)) {
            var dm = DOMAIN.matcher(lower);
            while (dm.find()) {
                String found = dm.group().replaceAll("\\s|\\(dot\\)|\\[dot]|punkt", ".").replaceAll("\\.+", ".");
                boolean whitelisted = false;
                for (String w : f.getStringList("domain-whitelist")) if (found.contains(w.toLowerCase())) whitelisted = true;
                if (!whitelisted) return blocked(p, msg, "Domain");
            }
        }
        boolean censor = "CENSOR".equalsIgnoreCase(f.getString("action", "BLOCK")) && canModify;
        String out = msg;
        for (String word : f.getStringList("words")) {
            if (word.isBlank()) continue;
            String w = word.toLowerCase(Locale.ROOT);
            if (normalize(lower).contains(normalize(w))) {
                if (!censor) return blocked(p, msg, "Wort: " + word);
                out = out.replaceAll("(?i)" + Pattern.quote(word), "*".repeat(word.length()));
            }
        }
        return out;
    }

    /** "H4ck3r" → "hacker" (einfache Umgehungen erkennen). */
    private static String normalize(String s) {
        return s.replace('4', 'a').replace('3', 'e').replace('1', 'i').replace('0', 'o').replace('5', 's').replace('$', 's')
                .replace('@', 'a').replaceAll("[^a-zäöüß]", "");
    }

    private String blocked(ProxiedPlayer p, String msg, String why) {
        m().send(p, "chat.filtered");
        plugin.notifier().notify("chat", "chat.filter-notify", "player", p.getName(), "message", msg.replace("[", "(").replace("]", ")"),
                "reason", why, "server", p.getServer() == null ? "?" : p.getServer().getInfo().getName());
        return null;
    }

    /** Für Privatnachrichten, Party- & Clan-Chat (darf geändert werden). */
    public String filterPrivate(ProxiedPlayer p, String msg) {
        if (!cfg().getBoolean("enabled", true)) return msg;
        return check(p, msg, true);
    }

    // ================================================================ Format

    private boolean formats(String server) {
        Configuration f = cfg().getSection("format");
        return f.getBoolean("enabled", true) && !plugin.matches(server, f.getStringList("ignored-servers"));
    }

    private void deliver(ProxiedPlayer sender, String message, String serverName) {
        Configuration f = cfg().getSection("format");
        boolean global = "GLOBAL".equalsIgnoreCase(f.getString("scope", "SERVER"));
        String[] meta = prefixSuffix(sender);
        String clanTag = plugin.clans() == null ? null : plugin.clans().tag(sender.getUniqueId());
        String clan = clanTag == null ? "" : Text.replace(f.getString("clan", "&8[&e{tag}&8] "), "tag", clanTag);
        String format = Text.replace(f.getString("format", "{clan}{prefix}{name}{suffix} &8» &7{message}"),
                "prefix", meta[0], "suffix", meta[1], "name", sender.getName(), "server", serverName, "clan", clan,
                "displayname", sender.getDisplayName());
        int idx = format.indexOf("{message}");
        String before = idx < 0 ? format : format.substring(0, idx);
        String after = idx < 0 ? "" : format.substring(idx + "{message}".length());
        BaseComponent[] head = Text.parse(before);
        BaseComponent[] tail = Text.parse(after);
        String baseColor = Text.lastColors(Text.color(before));
        boolean colors = sender.hasPermission("bungeesystem.chat.color");
        String body = colors ? Text.color(message) : message;

        Collection<ProxiedPlayer> targets = global || sender.getServer() == null
                ? ProxyServer.getInstance().getPlayers() : sender.getServer().getInfo().getPlayers();
        Configuration mention = cfg().getSection("mentions");
        boolean mentions = mention.getBoolean("enabled", true);
        for (ProxiedPlayer to : targets) {
            PlayerManager.Session s = plugin.players().session(to.getUniqueId());
            if (s != null && s.chatOff && to != sender) continue;
            String text = body;
            boolean mentioned = false;
            if (mentions && to != sender && containsName(text, to.getName())) {
                mentioned = true;
                String hl = Text.color(Text.replace(mention.getString("format", "&e&l@{name}"), "name", to.getName()));
                text = text.replaceAll("(?i)@?\\b" + Pattern.quote(to.getName()) + "\\b", java.util.regex.Matcher.quoteReplacement(hl + baseColor));
            }
            List<BaseComponent> line = new ArrayList<>(List.of(head));
            line.addAll(List.of(TextComponent.fromLegacyText(baseColor + text)));
            line.addAll(List.of(tail));
            to.sendMessage(line.toArray(new BaseComponent[0]));
            if (mentioned) m().actionbar(to, "chat.mention-actionbar", "player", sender.getName());
        }
        plugin.getLogger().info("[Chat/" + serverName + "] " + sender.getName() + ": " + message);
    }

    private void pingMentions(ProxiedPlayer sender, String message, ServerInfo server) {
        if (!cfg().getBoolean("mentions.enabled", true) || server == null) return;
        for (ProxiedPlayer to : server.getPlayers()) {
            if (to != sender && containsName(message, to.getName())) m().actionbar(to, "chat.mention-actionbar", "player", sender.getName());
        }
    }

    private static boolean containsName(String text, String name) {
        return Pattern.compile("(?i)\\b" + Pattern.quote(name) + "\\b").matcher(text).find();
    }

    /** Prefix & Suffix: LuckPerms (falls installiert) oder chat.format.groups. */
    private String[] prefixSuffix(ProxiedPlayer p) {
        Configuration f = cfg().getSection("format");
        if (f.getBoolean("use-luckperms", true)) {
            String[] lp = luckPerms(p);
            if (lp != null) return lp;
        }
        Configuration groups = f.getSection("groups");
        for (String key : groups.getKeys()) {
            Configuration g = groups.getSection(key);
            String perm = g.getString("permission", "");
            if (perm.isEmpty() || p.hasPermission(perm)) return new String[]{g.getString("prefix", ""), g.getString("suffix", "")};
        }
        return new String[]{"", ""};
    }

    private String[] luckPerms(ProxiedPlayer p) {
        try {
            if (!luckPermsChecked) {
                luckPermsChecked = true;
                if (ProxyServer.getInstance().getPluginManager().getPlugin("LuckPerms") != null) {
                    luckPerms = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
                }
            }
            if (luckPerms == null) return null;
            Object adapter = luckPerms.getClass().getMethod("getPlayerAdapter", Class.class).invoke(luckPerms, ProxiedPlayer.class);
            Method meta = adapter.getClass().getMethod("getMetaData", Object.class);
            meta.setAccessible(true);
            Object data = meta.invoke(adapter, p);
            Method pre = data.getClass().getMethod("getPrefix"), suf = data.getClass().getMethod("getSuffix");
            pre.setAccessible(true);
            suf.setAccessible(true);
            Object prefix = pre.invoke(data), suffix = suf.invoke(data);
            return new String[]{prefix == null ? "" : prefix.toString(), suffix == null ? "" : suffix.toString()};
        } catch (Throwable t) {
            if (luckPerms != null) plugin.getLogger().warning("LuckPerms-Prefix nicht lesbar: " + t);
            luckPerms = null;
            return null;
        }
    }

    // ================================================================ CMDBlock

    private void onCommand(ChatEvent e, ProxiedPlayer p, String msg) {
        String label = msg.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.isEmpty()) return;
        // Mute: Chat-Befehle der Spielserver sperren
        PunishManager.Punishment mute = plugin.punish().mute(p.getUniqueId());
        if (mute != null && plugin.config().getStringList("punish.mute-blocked-commands").contains(stripNamespace(label))) {
            e.setCancelled(true);
            Messages.sendRaw(p, plugin.punish().muteMessage(mute));
            return;
        }
        Configuration cb = plugin.config().getSection("cmdblock");
        if (!cb.getBoolean("enabled", true) || !plugin.moduleActive("cmdblock") || p.hasPermission("bungeesystem.cmdblock.bypass")) return;
        if (plugin.isOwnCommand(label)) return;
        boolean block = cb.getBoolean("block-namespaced", true) && label.contains(":");
        if (!block) {
            for (String c : cb.getStringList("commands")) {
                String x = c.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
                if (x.endsWith("*") ? label.startsWith(x.substring(0, x.length() - 1)) : label.equals(x)) {
                    block = true;
                    break;
                }
            }
        }
        if (block) {
            e.setCancelled(true);
            m().send(p, "cmdblock.blocked", "command", label);
        }
    }

    private static String stripNamespace(String label) {
        int i = label.indexOf(':');
        return i >= 0 ? label.substring(i + 1) : label;
    }

    // ================================================================ Befehle

    public static void register(BungeeSystem plugin) {
        plugin.command("globalmute", n -> new GlobalMuteCmd(plugin, n));
        plugin.command("chatclear", n -> new ClearCmd(plugin, n));
        plugin.command("chat", n -> new ToggleCmd(plugin, n));
    }

    static final class GlobalMuteCmd extends Cmd {
        GlobalMuteCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.chat.globalmute");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ChatModule c = plugin.chat();
            if (args.length > 0) {
                ServerInfo server = ProxyServer.getInstance().getServerInfo(args[0]);
                if (server == null) {
                    m().send(s, "general.server-not-found", "server", args[0]);
                    return;
                }
                String key = server.getName().toLowerCase();
                boolean on = !c.mutedServers.remove(key);
                if (on) c.mutedServers.add(key);
                for (ProxiedPlayer p : server.getPlayers()) m().send(p, on ? "chat.globalmute-on" : "chat.globalmute-off", "staff", name(s));
                m().send(s, "chat.globalmute-server", "server", server.getName(), "state", m().raw(on ? "general.on" : "general.off"));
                return;
            }
            c.globalMute = !c.globalMute;
            for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) m().send(p, c.globalMute ? "chat.globalmute-on" : "chat.globalmute-off", "staff", name(s));
            if (!(s instanceof ProxiedPlayer)) m().send(s, c.globalMute ? "chat.globalmute-on" : "chat.globalmute-off", "staff", name(s));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            return args.length == 1 ? filter(args[0], ProxyServer.getInstance().getServers().keySet()) : List.of();
        }
    }

    static final class ClearCmd extends Cmd {
        ClearCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.chat.clear");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            Collection<ProxiedPlayer> targets;
            if (args.length > 0 && !args[0].equalsIgnoreCase("global")) {
                ServerInfo server = ProxyServer.getInstance().getServerInfo(args[0]);
                if (server == null) {
                    m().send(s, "general.server-not-found", "server", args[0]);
                    return;
                }
                targets = server.getPlayers();
            } else {
                targets = ProxyServer.getInstance().getPlayers();
            }
            BaseComponent[] empty = TextComponent.fromLegacyText(" ");
            int lines = plugin.config().getInt("chat.clear-lines", 120);
            for (ProxiedPlayer p : targets) {
                if (!p.hasPermission("bungeesystem.chat.clear.bypass")) {
                    for (int i = 0; i < lines; i++) p.sendMessage(ChatMessageType.SYSTEM, empty);
                }
                m().send(p, "chat.cleared", "staff", name(s));
            }
            if (!(s instanceof ProxiedPlayer)) m().send(s, "chat.cleared", "staff", name(s));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length != 1) return List.of();
            List<String> out = new ArrayList<>(ProxyServer.getInstance().getServers().keySet());
            out.add("global");
            return filter(args[0], out);
        }
    }

    /**
     * /chat – ohne Argument: Chat für sich selbst an/aus. Unterbefehle: globalmute, clear [Spieler], filter list|add|remove.
     */
    static final class ToggleCmd extends Cmd {
        ToggleCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "toggle";
            switch (sub) {
                case "globalmute" -> {
                    if (!s.hasPermission("bungeesystem.chat.globalmute")) {
                        m().send(s, "general.no-permission");
                        return;
                    }
                    ProxyServer.getInstance().getPluginManager().dispatchCommand(s, plugin.commandName("globalmute") + (args.length > 1 ? " " + args[1] : ""));
                }
                case "clear" -> {
                    if (!s.hasPermission("bungeesystem.chat.clear")) {
                        m().send(s, "general.no-permission");
                        return;
                    }
                    if (args.length > 1) {
                        ProxiedPlayer t = ProxyServer.getInstance().getPlayer(args[1]);
                        if (t == null) {
                            ProxyServer.getInstance().getPluginManager().dispatchCommand(s, plugin.commandName("chatclear") + " " + args[1]);
                            return;
                        }
                        BaseComponent[] empty = TextComponent.fromLegacyText(" ");
                        for (int i = 0; i < plugin.config().getInt("chat.clear-lines", 120); i++) t.sendMessage(ChatMessageType.SYSTEM, empty);
                        m().send(t, "chat.cleared", "staff", name(s));
                        m().send(s, "chat.cleared-player", "player", t.getName());
                        return;
                    }
                    ProxyServer.getInstance().getPluginManager().dispatchCommand(s, plugin.commandName("chatclear"));
                }
                case "filter" -> {
                    if (!s.hasPermission("bungeesystem.chat.filter.manage")) {
                        m().send(s, "general.no-permission");
                        return;
                    }
                    List<String> words = new ArrayList<>(plugin.config().getStringList("chat.filter.words"));
                    String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
                    if ((action.equals("add") || action.equals("remove")) && args.length > 2) {
                        String word = join(args, 2).toLowerCase(Locale.ROOT);
                        boolean changed = action.equals("add") ? !words.contains(word) && words.add(word) : words.remove(word);
                        if (changed) {
                            plugin.config().set("chat.filter.words", words);
                            plugin.configFile().save();
                        }
                        m().send(s, action.equals("add") ? (changed ? "chat.filter-added" : "chat.filter-exists") : (changed ? "chat.filter-removed" : "chat.filter-missing"),
                                "word", word);
                        return;
                    }
                    m().send(s, "chat.filter-list", "count", Integer.toString(words.size()), "words", words.isEmpty() ? "-" : String.join(", ", words));
                }
                default -> {
                    ProxiedPlayer p = player(s);
                    if (p == null) return;
                    if (!p.hasPermission("bungeesystem.chat.toggle")) {
                        m().send(p, "general.no-permission");
                        return;
                    }
                    PlayerManager.Session session = plugin.players().session(p);
                    session.chatOff = !session.chatOff;
                    plugin.players().saveSettings(session);
                    m().send(p, session.chatOff ? "chat.toggle-off" : "chat.toggle-on");
                }
            }
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return filter(args[0], "toggle", "globalmute", "clear", "filter");
            if (args.length == 2 && args[0].equalsIgnoreCase("filter")) return filter(args[1], "list", "add", "remove");
            if (args.length == 2 && args[0].equalsIgnoreCase("clear")) return players(args[1]);
            if (args.length == 3 && args[0].equalsIgnoreCase("filter") && args[1].equalsIgnoreCase("remove"))
                return filter(args[2], plugin.config().getStringList("chat.filter.words"));
            return List.of();
        }
    }
}
