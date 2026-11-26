package de.crafterteam.bungeesystem.social;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.config.Configuration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Clans (gleiche Tabellen wie das LobbySystem): Gründen gegen Coins, Tag, Rollen, Clan-Chat. */
public final class ClanManager {

    public static final int MEMBER = 0, MODERATOR = 1, LEADER = 2;

    public record Clan(String id, String name, String tag, UUID owner, long created, Map<UUID, Integer> roles, Map<UUID, String> names) {
        public int role(UUID u) {
            return roles.getOrDefault(u, -1);
        }

        public UUID find(String name) {
            for (var e : names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
            return null;
        }
    }

    private final BungeeSystem plugin;
    /** Clan-Tag der Online-Spieler (für Chat-Format & Tab). */
    private final Map<UUID, String> tags = new ConcurrentHashMap<>();

    public ClanManager(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Configuration cfg() {
        return plugin.config().getSection("clan");
    }

    private String cmd() {
        return plugin.commandName("clan");
    }

    public String tag(UUID uuid) {
        return tags.get(uuid);
    }

    public String roleName(int role) {
        return m().raw(switch (role) {
            case LEADER -> "clan.role-leader";
            case MODERATOR -> "clan.role-moderator";
            default -> "clan.role-member";
        });
    }

    // ---------------------------------------------------------------- DB

    static Clan loadByMember(Connection con, UUID uuid) throws SQLException {
        String id = Database.string(con, "select clan_id from ls_clan_members where uuid = ?", uuid.toString());
        return id == null ? null : load(con, "id", id);
    }

    static Clan load(Connection con, String column, String value) throws SQLException {
        String id, name, tag;
        UUID owner;
        long created;
        String where = column.equals("id") ? "id = ?" : "lower(" + column + ") = lower(?)";
        try (PreparedStatement ps = con.prepareStatement("select id, name, tag, owner, created from ls_clans where " + where)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                id = rs.getString(1);
                name = rs.getString(2);
                tag = rs.getString(3);
                owner = UUID.fromString(rs.getString(4));
                created = rs.getLong(5);
            }
        }
        Map<UUID, Integer> roles = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement("select uuid, role from ls_clan_members where clan_id = ? order by role desc, joined")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) roles.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
            }
        }
        return new Clan(id, name, tag, owner, created, roles, Social.names(con, roles.keySet()));
    }

    public CompletableFuture<Clan> clanOf(UUID uuid) {
        return plugin.db().submit(con -> loadByMember(con, uuid));
    }

    /** Tag eines Spielers neu laden und an alle Server schicken (LobbySystem/Addon → Tab). */
    public void refresh(UUID uuid) {
        clanOf(uuid).thenAccept(clan -> {
            if (clan == null) tags.remove(uuid);
            else tags.put(uuid, clan.tag());
            plugin.bridge().clanTag(uuid, clan == null ? "" : clan.tag());
        });
    }

    private void refreshMembers(Clan clan) {
        if (clan == null) return;
        for (UUID u : clan.roles().keySet()) if (ProxyServer.getInstance().getPlayer(u) != null) refresh(u);
    }

    public void forget(UUID uuid) {
        tags.remove(uuid);
    }

    private void broadcast(Clan clan, String key, String... ph) {
        if (clan == null) return;
        String text = m().raw(key, ph);
        for (UUID u : clan.roles().keySet()) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(u);
            if (p != null) Messages.sendRaw(p, text);
        }
    }

    private void withClan(ProxiedPlayer p, int minRole, Consumer<Clan> action) {
        clanOf(p.getUniqueId()).thenAccept(clan -> {
            if (clan == null) m().send(p, "clan.not-in");
            else if (clan.role(p.getUniqueId()) < minRole) m().send(p, "clan.no-rights");
            else action.accept(clan);
        });
    }

    // ---------------------------------------------------------------- Aktionen

    public void create(ProxiedPlayer p, String name, String tag) {
        int nameMax = cfg().getInt("name-max", 16), tagMin = cfg().getInt("tag-min", 2), tagMax = Math.min(8, cfg().getInt("tag-max", 5));
        if (!name.matches("[A-Za-z0-9_]{2," + nameMax + "}")) {
            m().send(p, "clan.invalid-name", "max", Integer.toString(nameMax));
            return;
        }
        if (!tag.matches("[A-Za-z0-9]{" + tagMin + "," + tagMax + "}")) {
            m().send(p, "clan.invalid-tag", "min", Integer.toString(tagMin), "max", Integer.toString(tagMax));
            return;
        }
        for (String bad : cfg().getStringList("blocked-names")) {
            if (name.toLowerCase().contains(bad.toLowerCase()) || tag.toLowerCase().contains(bad.toLowerCase())) {
                m().send(p, "clan.blocked-name");
                return;
            }
        }
        String upper = tag.toUpperCase();
        long cost = p.hasPermission("bungeesystem.clan.free") ? 0 : cfg().getLong("cost", 5000);
        plugin.db().submit(con -> {
            if (loadByMember(con, p.getUniqueId()) != null) return "clan.already-in";
            if (load(con, "name", name) != null || load(con, "tag", upper) != null) return "clan.exists";
            return null;
        }).thenCompose(error -> {
            if (error != null) {
                m().send(p, error);
                return CompletableFuture.completedFuture(false);
            }
            return plugin.bridge().takeCoins(p.getUniqueId(), cost).thenApply(ok -> {
                if (!ok) m().send(p, "clan.cost", "cost", String.format("%,d", cost).replace(',', '.'));
                return ok;
            });
        }).thenAccept(ok -> {
            if (!ok) return;
            plugin.db().run(con -> {
                // nochmal prüfen (zwischenzeitlich vergeben?) – sonst Coins zurück
                if (loadByMember(con, p.getUniqueId()) != null || load(con, "name", name) != null || load(con, "tag", upper) != null) {
                    if (cost > 0) Database.exec(con, "update ls_players set coins = coins + ? where uuid = ?", cost, p.getUniqueId().toString());
                    m().send(p, "clan.exists");
                    return null;
                }
                String id = UUID.randomUUID().toString();
                long now = System.currentTimeMillis();
                Database.exec(con, "insert into ls_clans (id, name, tag, owner, created) values (?, ?, ?, ?, ?)", id, name, upper, p.getUniqueId().toString(), now);
                Database.exec(con, "insert into ls_clan_members (uuid, clan_id, role, joined) values (?, ?, ?, ?)", p.getUniqueId().toString(), id, LEADER, now);
                m().send(p, "clan.created", "name", name, "tag", upper, "cost", Long.toString(cost));
                refresh(p.getUniqueId());
                return null;
            });
        });
    }

    public void invite(ProxiedPlayer p, String name) {
        int max = cfg().getInt("max-members", 30);
        long expires = System.currentTimeMillis() + cfg().getInt("invite-seconds", 300) * 1000L;
        withClan(p, MODERATOR, clan -> plugin.players().lookup(name).thenAccept(opt -> {
            if (opt.isEmpty()) {
                m().send(p, "general.player-not-found", "player", name);
                return;
            }
            var t = opt.get();
            plugin.db().run(con -> {
                if (clan.roles().size() >= max) {
                    m().send(p, "clan.full", "max", Integer.toString(max));
                    return null;
                }
                if (loadByMember(con, t.uuid()) != null) {
                    m().send(p, "clan.target-in-clan", "player", t.name());
                    return null;
                }
                Database.exec(con, "delete from ls_clan_invites where clan_id = ? and uuid = ?", clan.id(), t.uuid().toString());
                Database.exec(con, "insert into ls_clan_invites (clan_id, uuid, expires) values (?, ?, ?)", clan.id(), t.uuid().toString(), expires);
                m().send(p, "clan.invite-sent", "player", t.name());
                m().send(ProxyServer.getInstance().getPlayer(t.uuid()), "clan.invite-received", "clan", clan.name(), "tag", clan.tag(), "player", p.getName(), "cmd", cmd());
                return null;
            });
        }));
    }

    public void accept(ProxiedPlayer p, String tag) {
        int max = cfg().getInt("max-members", 30);
        plugin.db().run(con -> {
            if (loadByMember(con, p.getUniqueId()) != null) {
                m().send(p, "clan.already-in");
                return null;
            }
            Clan clan = load(con, "tag", tag);
            if (clan == null || Database.longValue(con, "select expires from ls_clan_invites where clan_id = ? and uuid = ?", 0, clan.id(), p.getUniqueId().toString())
                    < System.currentTimeMillis()) {
                m().send(p, "clan.no-invite");
                return null;
            }
            if (clan.roles().size() >= max) {
                m().send(p, "clan.full", "max", Integer.toString(max));
                return null;
            }
            Database.exec(con, "delete from ls_clan_invites where uuid = ?", p.getUniqueId().toString());
            Database.exec(con, "insert into ls_clan_members (uuid, clan_id, role, joined) values (?, ?, ?, ?)", p.getUniqueId().toString(), clan.id(), MEMBER, System.currentTimeMillis());
            Clan updated = load(con, "id", clan.id());
            broadcast(updated, "clan.joined", "player", p.getName());
            refresh(p.getUniqueId());
            return null;
        });
    }

    public void deny(ProxiedPlayer p, String tag) {
        plugin.db().run(con -> {
            Clan clan = load(con, "tag", tag);
            int n = clan == null ? 0 : Database.exec(con, "delete from ls_clan_invites where clan_id = ? and uuid = ?", clan.id(), p.getUniqueId().toString());
            m().send(p, n > 0 ? "clan.denied" : "clan.no-invite", "tag", tag.toUpperCase());
            return null;
        });
    }

    public void leave(ProxiedPlayer p) {
        withClan(p, MEMBER, clan -> plugin.db().run(con -> {
            if (clan.role(p.getUniqueId()) == LEADER) {
                long leaders = clan.roles().values().stream().filter(r -> r == LEADER).count();
                if (leaders <= 1 && clan.roles().size() > 1) {
                    m().send(p, "clan.leader-cant-leave", "cmd", cmd());
                    return null;
                }
                if (clan.roles().size() <= 1) {
                    deleteClan(con, clan);
                    m().send(p, "clan.disbanded", "name", clan.name());
                    refresh(p.getUniqueId());
                    return null;
                }
            }
            Database.exec(con, "delete from ls_clan_members where uuid = ?", p.getUniqueId().toString());
            broadcast(clan, "clan.left", "player", p.getName());
            refresh(p.getUniqueId());
            return null;
        }));
    }

    public void kick(ProxiedPlayer p, String name) {
        withClan(p, MODERATOR, clan -> {
            UUID t = clan.find(name);
            if (t == null) {
                m().send(p, "clan.not-member", "player", name);
                return;
            }
            if (clan.role(t) >= clan.role(p.getUniqueId())) {
                m().send(p, "clan.no-rights");
                return;
            }
            plugin.db().run(con -> {
                Database.exec(con, "delete from ls_clan_members where uuid = ?", t.toString());
                broadcast(clan, "clan.kicked", "player", clan.names().get(t), "staff", p.getName());
                refresh(t);
                return null;
            });
        });
    }

    private void setRole(ProxiedPlayer p, String name, int delta) {
        withClan(p, LEADER, clan -> {
            UUID t = clan.find(name);
            if (t == null || t.equals(p.getUniqueId())) {
                m().send(p, "clan.not-member", "player", name);
                return;
            }
            int role = Math.max(MEMBER, Math.min(LEADER, clan.role(t) + delta));
            if (role == clan.role(t)) {
                m().send(p, "clan.role-unchanged", "player", clan.names().get(t), "role", roleName(role));
                return;
            }
            plugin.db().run(con -> {
                Database.exec(con, "update ls_clan_members set role = ? where uuid = ?", role, t.toString());
                broadcast(clan, "clan.role-changed", "player", clan.names().get(t), "role", roleName(role));
                return null;
            });
        });
    }

    public void disband(ProxiedPlayer p) {
        withClan(p, LEADER, clan -> plugin.db().run(con -> {
            broadcast(clan, "clan.disbanded", "name", clan.name());
            deleteClan(con, clan);
            refreshMembers(clan);
            return null;
        }));
    }

    private static void deleteClan(Connection con, Clan clan) throws SQLException {
        Database.exec(con, "delete from ls_clan_members where clan_id = ?", clan.id());
        Database.exec(con, "delete from ls_clan_invites where clan_id = ?", clan.id());
        Database.exec(con, "delete from ls_clans where id = ?", clan.id());
    }

    public void info(ProxiedPlayer p, String tag) {
        CompletableFuture<Clan> f = tag == null ? clanOf(p.getUniqueId()) : plugin.db().submit(con -> {
            Clan c = load(con, "tag", tag);
            return c != null ? c : load(con, "name", tag);
        });
        f.thenAccept(clan -> {
            if (clan == null) {
                m().send(p, tag == null ? "clan.not-in" : "clan.not-found", "tag", tag == null ? "" : tag);
                return;
            }
            long online = clan.roles().keySet().stream().filter(u -> ProxyServer.getInstance().getPlayer(u) != null).count();
            m().send(p, "clan.info", "name", clan.name(), "tag", clan.tag(), "owner", clan.names().getOrDefault(clan.owner(), "?"),
                    "members", Integer.toString(clan.roles().size()), "online", Long.toString(online), "max", Integer.toString(cfg().getInt("max-members", 30)),
                    "date", Time.date(clan.created()));
            List<String> entries = new ArrayList<>();
            for (var e : clan.roles().entrySet()) {
                boolean on = ProxyServer.getInstance().getPlayer(e.getKey()) != null;
                entries.add(m().raw(on ? "clan.member-online" : "clan.member-offline", "player", clan.names().get(e.getKey()), "role", roleName(e.getValue())));
            }
            for (String line : entries) Messages.sendRaw(p, line);
        });
    }

    public void chat(ProxiedPlayer p, String message) {
        var mute = plugin.punish().mute(p.getUniqueId());
        if (mute != null) {
            Messages.sendRaw(p, plugin.punish().muteMessage(mute));
            return;
        }
        String clean = plugin.chat() != null ? plugin.chat().filterPrivate(p, message) : message;
        if (clean == null) return;
        withClan(p, MEMBER, clan -> broadcast(clan, "clan.chat", "player", p.getName(), "message", clean, "tag", clan.tag(),
                "role", roleName(clan.role(p.getUniqueId()))));
    }

    // ================================================================ Befehle

    public static void register(BungeeSystem plugin) {
        plugin.command("clan", n -> new ClanCmd(plugin, n));
        plugin.command("clanchat", n -> new ClanChatCmd(plugin, n));
    }

    static final class ClanCmd extends Cmd {
        ClanCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            ClanManager c = plugin.clans();
            String sub = args.length == 0 ? "info" : args[0].toLowerCase();
            String arg = args.length > 1 ? args[1] : null;
            switch (sub) {
                case "create", "gründen" -> {
                    if (args.length < 3) m().send(p, "clan.usage-create", "cmd", getName(),
                            "cost", Long.toString(plugin.config().getLong("clan.cost", 5000)));
                    else c.create(p, args[1], args[2]);
                }
                case "invite", "einladen" -> {
                    if (arg == null) m().send(p, "clan.help", "cmd", getName());
                    else c.invite(p, arg);
                }
                case "accept", "annehmen" -> {
                    if (arg == null) m().send(p, "clan.help", "cmd", getName());
                    else c.accept(p, arg);
                }
                case "deny", "ablehnen" -> {
                    if (arg == null) m().send(p, "clan.help", "cmd", getName());
                    else c.deny(p, arg);
                }
                case "leave", "verlassen" -> c.leave(p);
                case "kick" -> {
                    if (arg == null) m().send(p, "clan.help", "cmd", getName());
                    else c.kick(p, arg);
                }
                case "promote" -> {
                    if (arg == null) m().send(p, "clan.help", "cmd", getName());
                    else c.setRole(p, arg, 1);
                }
                case "demote" -> {
                    if (arg == null) m().send(p, "clan.help", "cmd", getName());
                    else c.setRole(p, arg, -1);
                }
                case "disband", "auflösen" -> c.disband(p);
                case "info", "list" -> c.info(p, arg);
                case "chat" -> {
                    if (args.length < 2) m().send(p, "clan.help", "cmd", getName());
                    else c.chat(p, join(args, 1));
                }
                default -> m().send(p, "clan.help", "cmd", getName());
            }
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return filter(args[0], "create", "invite", "accept", "deny", "leave", "kick", "promote", "demote", "disband", "info", "chat", "help");
            if (args.length == 2 && List.of("invite", "kick", "promote", "demote").contains(args[0].toLowerCase())) return players(args[1]);
            return List.of();
        }
    }

    static final class ClanChatCmd extends Cmd {
        ClanChatCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            if (args.length == 0) m().send(p, "clan.help", "cmd", plugin.commandName("clan"));
            else plugin.clans().chat(p, join(args, 0));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            return List.of();
        }
    }
}
