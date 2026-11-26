package de.crafterteam.bungeesystem.social;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.core.PlayerManager.Known;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Freundesystem auf dem Proxy – gleiche Tabellen wie das LobbySystem (ls_friends, ls_friend_requests). */
public final class FriendManager {

    public record Friend(UUID uuid, String name, ProxiedPlayer online, long lastSeen) {
    }

    private final BungeeSystem plugin;
    /** Letzter Gesprächspartner für /r */
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    public FriendManager(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private String cmd() {
        return plugin.commandName("friend");
    }

    public int max(ProxiedPlayer p) {
        return p.hasPermission("bungeesystem.friends.premium")
                ? plugin.config().getInt("friends.max-premium", 200) : plugin.config().getInt("friends.max", 50);
    }

    static List<UUID> ids(Connection con, UUID uuid) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("select friend from ls_friends where uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(UUID.fromString(rs.getString(1)));
            }
        }
        return out;
    }

    public static boolean areFriends(Connection con, UUID a, UUID b) throws SQLException {
        return Database.exists(con, "select 1 from ls_friends where uuid = ? and friend = ?", a.toString(), b.toString());
    }

    public CompletableFuture<Boolean> areFriends(UUID a, UUID b) {
        return plugin.db().submit(con -> areFriends(con, a, b));
    }

    public CompletableFuture<List<Friend>> friends(UUID uuid) {
        return plugin.db().submit(con -> {
            List<UUID> ids = ids(con, uuid);
            Map<UUID, String> names = Social.names(con, ids);
            List<Friend> list = new ArrayList<>();
            for (UUID id : ids) {
                ProxiedPlayer online = ProxyServer.getInstance().getPlayer(id);
                list.add(new Friend(id, online != null ? online.getName() : names.get(id), online, online != null ? 0 : Social.lastSeen(con, id)));
            }
            list.sort(Comparator.comparing((Friend f) -> f.online() == null).thenComparing(f -> f.name().toLowerCase()));
            return list;
        });
    }

    private void target(ProxiedPlayer p, String name, Consumer<Known> then) {
        plugin.players().lookup(name).thenAccept(opt -> {
            if (opt.isEmpty()) m().send(p, "general.player-not-found", "player", name);
            else then.accept(opt.get());
        });
    }

    // ---------------------------------------------------------------- Aktionen

    public void add(ProxiedPlayer p, String name) {
        if (p.getName().equalsIgnoreCase(name)) {
            m().send(p, "friend.self");
            return;
        }
        int max = max(p);
        target(p, name, t -> plugin.db().run(con -> {
            String me = p.getUniqueId().toString(), them = t.uuid().toString();
            if (areFriends(con, p.getUniqueId(), t.uuid())) {
                m().send(p, "friend.already", "player", t.name());
                return null;
            }
            if (Database.exists(con, "select 1 from ls_friend_requests where sender = ? and receiver = ?", them, me)) {
                befriend(con, p.getUniqueId(), t.uuid());
                m().send(p, "friend.added", "player", t.name());
                m().send(ProxyServer.getInstance().getPlayer(t.uuid()), "friend.added", "player", p.getName());
                return null;
            }
            if (Database.exists(con, "select 1 from ls_friend_requests where sender = ? and receiver = ?", me, them)) {
                m().send(p, "friend.request-pending", "player", t.name());
                return null;
            }
            if (!Social.setting(Social.settings(con, t.uuid()), "req")) {
                m().send(p, "friend.requests-disabled", "player", t.name());
                return null;
            }
            if (Database.longValue(con, "select count(*) from ls_friends where uuid = ?", 0, me) >= max) {
                m().send(p, "friend.limit", "max", Integer.toString(max));
                return null;
            }
            Database.exec(con, "insert into ls_friend_requests (sender, receiver, created) values (?, ?, ?)", me, them, System.currentTimeMillis());
            m().send(p, "friend.request-sent", "player", t.name());
            m().send(ProxyServer.getInstance().getPlayer(t.uuid()), "friend.request-received", "player", p.getName(), "cmd", cmd());
            return null;
        }));
    }

    private void befriend(Connection con, UUID a, UUID b) throws SQLException {
        long now = System.currentTimeMillis();
        String sa = a.toString(), sb = b.toString();
        Database.exec(con, "delete from ls_friend_requests where (sender = ? and receiver = ?) or (sender = ? and receiver = ?)", sa, sb, sb, sa);
        Database.exec(con, "delete from ls_friends where (uuid = ? and friend = ?) or (uuid = ? and friend = ?)", sa, sb, sb, sa);
        Database.exec(con, "insert into ls_friends (uuid, friend, since) values (?, ?, ?)", sa, sb, now);
        Database.exec(con, "insert into ls_friends (uuid, friend, since) values (?, ?, ?)", sb, sa, now);
        plugin.bridge().friendRefresh(a);
        plugin.bridge().friendRefresh(b);
    }

    public void accept(ProxiedPlayer p, String name) {
        int max = max(p);
        target(p, name, t -> plugin.db().run(con -> {
            if (!Database.exists(con, "select 1 from ls_friend_requests where sender = ? and receiver = ?", t.uuid().toString(), p.getUniqueId().toString())) {
                m().send(p, "friend.no-request", "player", t.name());
                return null;
            }
            if (Database.longValue(con, "select count(*) from ls_friends where uuid = ?", 0, p.getUniqueId().toString()) >= max) {
                m().send(p, "friend.limit", "max", Integer.toString(max));
                return null;
            }
            befriend(con, p.getUniqueId(), t.uuid());
            m().send(p, "friend.added", "player", t.name());
            m().send(ProxyServer.getInstance().getPlayer(t.uuid()), "friend.added", "player", p.getName());
            return null;
        }));
    }

    public void deny(ProxiedPlayer p, String name) {
        target(p, name, t -> plugin.db().run(con -> {
            int n = Database.exec(con, "delete from ls_friend_requests where sender = ? and receiver = ?", t.uuid().toString(), p.getUniqueId().toString());
            m().send(p, n > 0 ? "friend.denied" : "friend.no-request", "player", t.name());
            return null;
        }));
    }

    public void remove(ProxiedPlayer p, String name) {
        target(p, name, t -> plugin.db().run(con -> {
            String a = p.getUniqueId().toString(), b = t.uuid().toString();
            int n = Database.exec(con, "delete from ls_friends where (uuid = ? and friend = ?) or (uuid = ? and friend = ?)", a, b, b, a);
            if (n == 0) {
                m().send(p, "friend.not-friends", "player", t.name());
                return null;
            }
            m().send(p, "friend.removed", "player", t.name());
            plugin.bridge().friendRefresh(p.getUniqueId());
            plugin.bridge().friendRefresh(t.uuid());
            return null;
        }));
    }

    public void list(ProxiedPlayer p, int page) {
        friends(p.getUniqueId()).thenAccept(list -> {
            int per = 10, pages = Math.max(1, (list.size() + per - 1) / per);
            int pg = Math.max(1, Math.min(page, pages));
            long online = list.stream().filter(f -> f.online() != null).count();
            m().send(p, "friend.list-header", "count", Integer.toString(list.size()), "online", Long.toString(online),
                    "page", Integer.toString(pg), "pages", Integer.toString(pages), "max", Integer.toString(max(p)));
            if (list.isEmpty()) m().send(p, "friend.list-empty", "cmd", cmd());
            for (Friend f : list.subList((pg - 1) * per, Math.min(list.size(), pg * per))) {
                if (f.online() != null) {
                    String server = f.online().getServer() == null ? "?" : f.online().getServer().getInfo().getName();
                    m().send(p, "friend.list-online", "player", f.name(), "server", server, "cmd", cmd(), "party", plugin.commandName("party"));
                } else {
                    m().send(p, "friend.list-offline", "player", f.name(), "date", f.lastSeen() > 0 ? Time.date(f.lastSeen()) : "-", "cmd", cmd());
                }
            }
            if (pages > 1) m().send(p, "friend.list-footer", "page", Integer.toString(pg), "pages", Integer.toString(pages),
                    "next", Integer.toString(Math.min(pages, pg + 1)), "prev", Integer.toString(Math.max(1, pg - 1)), "cmd", cmd());
        });
    }

    public void requests(ProxiedPlayer p) {
        plugin.db().submit(con -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("select sender from ls_friend_requests where receiver = ? order by created")) {
                ps.setString(1, p.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(UUID.fromString(rs.getString(1)));
                }
            }
            return Social.names(con, ids);
        }).thenAccept(names -> {
            m().send(p, "friend.requests-header", "count", Integer.toString(names.size()));
            for (String n : names.values()) m().send(p, "friend.request-received", "player", n, "cmd", cmd());
        });
    }

    public void jump(ProxiedPlayer p, String name) {
        ProxiedPlayer t = ProxyServer.getInstance().getPlayer(name);
        if (t == null) {
            m().send(p, "general.player-offline", "player", name);
            return;
        }
        areFriends(p.getUniqueId(), t.getUniqueId()).thenAccept(ok -> {
            if (!ok) {
                m().send(p, "friend.not-friends", "player", t.getName());
                return;
            }
            if (t.getServer() == null) return;
            if (p.getServer() != null && p.getServer().getInfo().equals(t.getServer().getInfo())) {
                m().send(p, "friend.same-server", "player", t.getName());
                return;
            }
            if (plugin.matches(t.getServer().getInfo().getName(), plugin.config().getStringList("friends.no-jump-servers"))
                    && !p.hasPermission("bungeesystem.friends.jump.bypass")) {
                m().send(p, "friend.jump-blocked", "server", t.getServer().getInfo().getName());
                return;
            }
            m().send(p, "friend.jump", "player", t.getName(), "server", t.getServer().getInfo().getName());
            p.connect(t.getServer().getInfo());
        });
    }

    public void toggle(ProxiedPlayer p, String key, String msgKey) {
        plugin.db().submit(con -> {
            boolean[] state = new boolean[1];
            String settings = Social.toggle(con, p.getUniqueId(), key, state);
            plugin.bridge().friendSettings(p.getUniqueId(), settings);
            return state[0];
        }).thenAccept(on -> m().send(p, msgKey, "state", m().raw(on ? "general.on" : "general.off")));
    }

    // ---------------------------------------------------------------- Privatnachrichten

    public void message(ProxiedPlayer from, String toName, String text, boolean friendsOnly) {
        ProxiedPlayer to = ProxyServer.getInstance().getPlayer(toName);
        if (to == null) {
            m().send(from, "general.player-offline", "player", toName);
            return;
        }
        if (to.equals(from)) {
            m().send(from, "msg.self");
            return;
        }
        var mute = plugin.punish().mute(from.getUniqueId());
        if (mute != null) {
            Messages.sendRaw(from, plugin.punish().muteMessage(mute));
            return;
        }
        boolean bypass = from.hasPermission("bungeesystem.msg.bypass");
        areFriends(from.getUniqueId(), to.getUniqueId()).thenAccept(friends -> {
            if (friendsOnly && !friends && !bypass) {
                m().send(from, "friend.not-friends", "player", to.getName());
                return;
            }
            String clean = plugin.chat() != null ? plugin.chat().filterPrivate(from, text) : text;
            if (clean == null) return;
            lastPartner.put(from.getUniqueId(), to.getUniqueId());
            lastPartner.put(to.getUniqueId(), from.getUniqueId());
            String key = friends ? "msg.friend" : "msg.private";
            m().send(from, key + "-sent", "player", to.getName(), "message", clean, "cmd", plugin.commandName("msg"));
            m().send(to, key + "-received", "player", from.getName(), "message", clean, "cmd", plugin.commandName("msg"));
            for (ProxiedPlayer spy : ProxyServer.getInstance().getPlayers()) {
                if (spy != from && spy != to && spy.hasPermission("bungeesystem.msg.spy") && plugin.notifier().receives(spy, "chat")) {
                    m().send(spy, "msg.spy", "from", from.getName(), "to", to.getName(), "message", clean);
                }
            }
        });
    }

    public UUID lastPartner(UUID uuid) {
        return lastPartner.get(uuid);
    }

    public void forget(UUID uuid) {
        lastPartner.remove(uuid);
    }

    // ---------------------------------------------------------------- Join / Quit

    public void announce(ProxiedPlayer p, boolean online) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        plugin.db().submit(con -> {
            if (!Social.setting(Social.settings(con, id), "notify")) return List.<UUID>of();
            List<UUID> out = new ArrayList<>();
            for (UUID f : ids(con, id)) {
                ProxiedPlayer fp = ProxyServer.getInstance().getPlayer(f);
                if (fp != null && Social.setting(Social.settings(con, f), "notify")) out.add(f);
            }
            if (online) {
                long requests = Database.longValue(con, "select count(*) from ls_friend_requests where receiver = ?", 0, id.toString());
                if (requests > 0) m().send(p, "friend.pending-requests", "count", Long.toString(requests), "cmd", cmd());
            }
            return out;
        }).thenAccept(list -> {
            for (UUID f : list) {
                ProxiedPlayer fp = ProxyServer.getInstance().getPlayer(f);
                if (fp != null) m().send(fp, online ? "friend.online" : "friend.offline", "player", name);
                plugin.bridge().friendRefresh(f);
            }
        });
    }

    // ================================================================ Befehle

    public static void register(BungeeSystem plugin) {
        plugin.command("friend", n -> new FriendCmd(plugin, n));
        if (plugin.config().getBoolean("private-messages.enabled", true)) {
            plugin.command("msg", n -> new MsgCmd(plugin, n));
            plugin.command("reply", n -> new ReplyCmd(plugin, n));
        }
    }

    static final class FriendCmd extends Cmd {
        FriendCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            FriendManager f = plugin.friends();
            String sub = args.length == 0 ? "list" : args[0].toLowerCase();
            switch (sub) {
                case "add", "hinzufügen" -> {
                    if (args.length < 2) m().send(p, "friend.usage", "cmd", getName());
                    else f.add(p, args[1]);
                }
                case "remove", "entfernen", "delete" -> {
                    if (args.length < 2) m().send(p, "friend.usage", "cmd", getName());
                    else f.remove(p, args[1]);
                }
                case "accept", "annehmen" -> {
                    if (args.length < 2) f.requests(p);
                    else f.accept(p, args[1]);
                }
                case "deny", "ablehnen" -> {
                    if (args.length < 2) f.requests(p);
                    else f.deny(p, args[1]);
                }
                case "list", "liste" -> f.list(p, args.length > 1 ? parse(args[1]) : 1);
                case "requests", "anfragen" -> f.requests(p);
                case "jump", "join", "nachspringen" -> {
                    if (args.length < 2) m().send(p, "friend.usage", "cmd", getName());
                    else f.jump(p, args[1]);
                }
                case "msg", "message", "nachricht" -> {
                    if (args.length < 3) m().send(p, "friend.usage", "cmd", getName());
                    else f.message(p, args[1], join(args, 2), true);
                }
                case "toggle", "requests-toggle" -> f.toggle(p, "req", "friend.toggle-requests");
                case "notify", "benachrichtigung" -> f.toggle(p, "notify", "friend.toggle-notify");
                case "help", "hilfe" -> m().send(p, "friend.help", "cmd", getName());
                default -> {
                    // "/friend Name" = Anfrage senden
                    if (args.length == 1) f.add(p, args[0]);
                    else m().send(p, "friend.help", "cmd", getName());
                }
            }
        }

        private static int parse(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 1;
            }
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return filter(args[0], "add", "remove", "accept", "deny", "list", "requests", "jump", "msg", "toggle", "notify", "help");
            if (args.length == 2) return players(args[1]);
            return List.of();
        }
    }

    static final class MsgCmd extends Cmd {
        MsgCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            if (args.length < 2) {
                m().send(p, "msg.usage", "cmd", getName());
                return;
            }
            plugin.friends().message(p, args[0], join(args, 1), plugin.config().getBoolean("private-messages.only-friends", false));
        }
    }

    static final class ReplyCmd extends Cmd {
        ReplyCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            if (args.length == 0) {
                m().send(p, "msg.reply-usage", "cmd", getName());
                return;
            }
            UUID last = plugin.friends().lastPartner(p.getUniqueId());
            ProxiedPlayer to = last == null ? null : ProxyServer.getInstance().getPlayer(last);
            if (to == null) {
                m().send(p, "msg.no-reply");
                return;
            }
            plugin.friends().message(p, to.getName(), join(args, 0), plugin.config().getBoolean("private-messages.only-friends", false));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            return List.of();
        }
    }
}
