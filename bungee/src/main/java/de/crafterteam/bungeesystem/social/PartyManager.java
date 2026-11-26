package de.crafterteam.bungeesystem.social;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Party-System auf dem Proxy (Tabellen ls_parties / ls_party_members / ls_party_invites wie im LobbySystem):
 * Mitglieder folgen dem Leader auf jeden Server, Party-Chat, Einladen, Kick, Leader übergeben.
 */
public final class PartyManager {

    public record Party(String id, UUID leader, List<UUID> members, Map<UUID, String> names) {
        public boolean isLeader(UUID u) {
            return leader.equals(u);
        }

        public UUID find(String name) {
            for (var e : names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
            return null;
        }
    }

    private final BungeeSystem plugin;

    public PartyManager(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private String cmd() {
        return plugin.commandName("party");
    }

    private int max(ProxiedPlayer leader) {
        return Math.max(2, leader != null && leader.hasPermission("bungeesystem.party.premium")
                ? plugin.config().getInt("party.max-premium", 16) : plugin.config().getInt("party.max", 8));
    }

    static Party load(Connection con, UUID member) throws SQLException {
        String id = Database.string(con, "select party_id from ls_party_members where uuid = ?", member.toString());
        if (id == null) return null;
        String leader = Database.string(con, "select leader from ls_parties where id = ?", id);
        if (leader == null) {
            Database.exec(con, "delete from ls_party_members where party_id = ?", id);
            return null;
        }
        List<UUID> members = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("select uuid from ls_party_members where party_id = ? order by joined")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) members.add(UUID.fromString(rs.getString(1)));
            }
        }
        return new Party(id, UUID.fromString(leader), members, Social.names(con, members));
    }

    public CompletableFuture<Party> party(UUID uuid) {
        return plugin.db().submit(con -> load(con, uuid));
    }

    private void broadcast(Party party, String key, String... ph) {
        if (party == null) return;
        String text = m().raw(key, ph);
        for (UUID u : party.members()) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(u);
            if (p != null) Messages.sendRaw(p, text);
        }
    }

    private static void disband(Connection con, String id) throws SQLException {
        Database.exec(con, "delete from ls_party_members where party_id = ?", id);
        Database.exec(con, "delete from ls_party_invites where party_id = ?", id);
        Database.exec(con, "delete from ls_parties where id = ?", id);
    }

    // ---------------------------------------------------------------- Aktionen

    public void invite(ProxiedPlayer p, String name) {
        ProxiedPlayer t = ProxyServer.getInstance().getPlayer(name);
        if (t == null) {
            m().send(p, "general.player-offline", "player", name);
            return;
        }
        if (t.equals(p)) {
            m().send(p, "party.self");
            return;
        }
        int max = max(p);
        long expires = System.currentTimeMillis() + plugin.config().getInt("party.invite-seconds", 60) * 1000L;
        plugin.db().run(con -> {
            Party party = load(con, p.getUniqueId());
            if (party == null) {
                String id = UUID.randomUUID().toString();
                long now = System.currentTimeMillis();
                Database.exec(con, "insert into ls_parties (id, leader, created) values (?, ?, ?)", id, p.getUniqueId().toString(), now);
                Database.exec(con, "insert into ls_party_members (uuid, party_id, joined) values (?, ?, ?)", p.getUniqueId().toString(), id, now);
                party = load(con, p.getUniqueId());
            }
            if (!party.isLeader(p.getUniqueId())) {
                m().send(p, "party.not-leader");
                return null;
            }
            if (party.members().contains(t.getUniqueId())) {
                m().send(p, "party.already-member", "player", t.getName());
                return null;
            }
            if (party.members().size() >= max) {
                m().send(p, "party.full", "max", Integer.toString(max));
                return null;
            }
            Party other = load(con, t.getUniqueId());
            if (other != null && other.members().size() > 1) {
                m().send(p, "party.target-in-party", "player", t.getName());
                return null;
            }
            if (!Social.setting(Social.settings(con, t.getUniqueId()), "party") && !FriendManager.areFriends(con, p.getUniqueId(), t.getUniqueId())) {
                m().send(p, "party.invites-disabled", "player", t.getName());
                return null;
            }
            Database.exec(con, "delete from ls_party_invites where party_id = ? and uuid = ?", party.id(), t.getUniqueId().toString());
            Database.exec(con, "insert into ls_party_invites (party_id, uuid, expires) values (?, ?, ?)", party.id(), t.getUniqueId().toString(), expires);
            m().send(p, "party.invite-sent", "player", t.getName());
            m().send(t, "party.invite-received", "player", p.getName(), "cmd", cmd(),
                    "seconds", Integer.toString(plugin.config().getInt("party.invite-seconds", 60)));
            return null;
        });
    }

    private static boolean inviteValid(Connection con, String partyId, UUID uuid) throws SQLException {
        return Database.longValue(con, "select expires from ls_party_invites where party_id = ? and uuid = ?", 0, partyId, uuid.toString())
                > System.currentTimeMillis();
    }

    public void accept(ProxiedPlayer p, String leaderName) {
        ProxiedPlayer leader = ProxyServer.getInstance().getPlayer(leaderName);
        if (leader == null) {
            m().send(p, "general.player-offline", "player", leaderName);
            return;
        }
        int max = max(leader);
        plugin.db().run(con -> {
            Party own = load(con, p.getUniqueId());
            if (own != null) {
                if (own.members().size() > 1) {
                    m().send(p, "party.already-in");
                    return null;
                }
                disband(con, own.id());
            }
            Party party = load(con, leader.getUniqueId());
            if (party == null || !inviteValid(con, party.id(), p.getUniqueId())) {
                m().send(p, "party.no-invite", "player", leader.getName());
                return null;
            }
            if (party.members().size() >= max) {
                m().send(p, "party.full", "max", Integer.toString(max));
                return null;
            }
            Database.exec(con, "delete from ls_party_invites where uuid = ?", p.getUniqueId().toString());
            Database.exec(con, "insert into ls_party_members (uuid, party_id, joined) values (?, ?, ?)", p.getUniqueId().toString(), party.id(), System.currentTimeMillis());
            broadcast(load(con, p.getUniqueId()), "party.joined", "player", p.getName());
            // direkt auf den Server des Leaders
            ProxiedPlayer l = ProxyServer.getInstance().getPlayer(party.leader());
            if (l != null && l.getServer() != null && plugin.config().getBoolean("party.follow", true)
                    && (p.getServer() == null || !p.getServer().getInfo().equals(l.getServer().getInfo()))) {
                p.connect(l.getServer().getInfo());
            }
            return null;
        });
    }

    public void deny(ProxiedPlayer p, String leaderName) {
        plugin.players().lookup(leaderName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                m().send(p, "general.player-not-found", "player", leaderName);
                return;
            }
            plugin.db().run(con -> {
                Party party = load(con, opt.get().uuid());
                int n = party == null ? 0 : Database.exec(con, "delete from ls_party_invites where party_id = ? and uuid = ?", party.id(), p.getUniqueId().toString());
                m().send(p, n > 0 ? "party.denied" : "party.no-invite", "player", opt.get().name());
                ProxiedPlayer l = ProxyServer.getInstance().getPlayer(opt.get().uuid());
                if (n > 0) m().send(l, "party.denied-leader", "player", p.getName());
                return null;
            });
        });
    }

    public void leave(ProxiedPlayer p) {
        plugin.db().run(con -> {
            Party party = load(con, p.getUniqueId());
            if (party == null) {
                m().send(p, "party.not-in");
                return null;
            }
            removeMember(con, party, p.getUniqueId(), "party.left");
            return null;
        });
    }

    private void removeMember(Connection con, Party party, UUID uuid, String key) throws SQLException {
        broadcast(party, key, "player", party.names().getOrDefault(uuid, "?"));
        Database.exec(con, "delete from ls_party_members where uuid = ?", uuid.toString());
        List<UUID> rest = party.members().stream().filter(u -> !u.equals(uuid)).toList();
        if (rest.size() < 2) {
            if (!rest.isEmpty()) m().send(ProxyServer.getInstance().getPlayer(rest.get(0)), "party.disbanded");
            disband(con, party.id());
            return;
        }
        if (party.isLeader(uuid)) {
            UUID next = rest.stream().filter(u -> ProxyServer.getInstance().getPlayer(u) != null).findFirst().orElse(rest.get(0));
            Database.exec(con, "update ls_parties set leader = ? where id = ?", next.toString(), party.id());
            broadcast(load(con, next), "party.promoted", "player", party.names().getOrDefault(next, "?"));
        }
    }

    private void leaderAction(ProxiedPlayer p, Consumer<Party> action) {
        party(p.getUniqueId()).thenAccept(party -> {
            if (party == null) m().send(p, "party.not-in");
            else if (!party.isLeader(p.getUniqueId())) m().send(p, "party.not-leader");
            else action.accept(party);
        });
    }

    public void kick(ProxiedPlayer p, String name) {
        leaderAction(p, party -> {
            UUID target = party.find(name);
            if (target == null || target.equals(p.getUniqueId())) {
                m().send(p, "party.not-member", "player", name);
                return;
            }
            plugin.db().run(con -> {
                removeMember(con, party, target, "party.kicked");
                return null;
            });
        });
    }

    public void promote(ProxiedPlayer p, String name) {
        leaderAction(p, party -> {
            UUID target = party.find(name);
            if (target == null || target.equals(p.getUniqueId())) {
                m().send(p, "party.not-member", "player", name);
                return;
            }
            plugin.db().run(con -> {
                Database.exec(con, "update ls_parties set leader = ? where id = ?", target.toString(), party.id());
                broadcast(party, "party.promoted", "player", party.names().get(target));
                return null;
            });
        });
    }

    public void disband(ProxiedPlayer p) {
        leaderAction(p, party -> plugin.db().run(con -> {
            broadcast(party, "party.disbanded");
            disband(con, party.id());
            return null;
        }));
    }

    public void list(ProxiedPlayer p) {
        party(p.getUniqueId()).thenAccept(party -> {
            if (party == null) {
                m().send(p, "party.not-in");
                return;
            }
            ProxiedPlayer leader = ProxyServer.getInstance().getPlayer(party.leader());
            m().send(p, "party.list-header", "count", Integer.toString(party.members().size()), "max", Integer.toString(max(leader)));
            for (UUID u : party.members()) {
                ProxiedPlayer on = ProxyServer.getInstance().getPlayer(u);
                m().send(p, on != null ? "party.list-online" : "party.list-offline", "player", party.names().get(u),
                        "role", m().raw(party.isLeader(u) ? "party.role-leader" : "party.role-member"),
                        "server", on != null && on.getServer() != null ? on.getServer().getInfo().getName() : "-");
            }
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
        party(p.getUniqueId()).thenAccept(party -> {
            if (party == null) m().send(p, "party.not-in");
            else broadcast(party, "party.chat", "player", p.getName(), "message", clean);
        });
    }

    /** /party warp – alle Mitglieder auf den Server des Leaders holen. */
    public void warp(ProxiedPlayer p) {
        leaderAction(p, party -> {
            if (p.getServer() == null) return;
            m().send(p, "party.warp", "server", p.getServer().getInfo().getName());
            pull(party, p.getServer().getInfo(), p.getUniqueId());
        });
    }

    /** /party join – zum Server des Leaders springen. */
    public void join(ProxiedPlayer p) {
        party(p.getUniqueId()).thenAccept(party -> {
            if (party == null) {
                m().send(p, "party.not-in");
                return;
            }
            ProxiedPlayer l = ProxyServer.getInstance().getPlayer(party.leader());
            if (l == null || l.getServer() == null || l.equals(p)) return;
            p.connect(l.getServer().getInfo());
        });
    }

    private void pull(Party party, ServerInfo server, UUID leader) {
        for (UUID u : party.members()) {
            if (u.equals(leader)) continue;
            ProxiedPlayer member = ProxyServer.getInstance().getPlayer(u);
            if (member == null) continue;
            if (member.getServer() != null && member.getServer().getInfo().equals(server)) continue;
            m().send(member, "party.follow", "server", server.getName());
            member.connect(server);
        }
    }

    /** Leader wechselt den Server → Party folgt. */
    public void onSwitch(ProxiedPlayer p, ServerInfo server) {
        if (!plugin.config().getBoolean("party.follow", true)) return;
        if (plugin.matches(server.getName(), plugin.config().getStringList("party.follow-ignore"))) return;
        party(p.getUniqueId()).thenAccept(party -> {
            if (party != null && party.isLeader(p.getUniqueId()) && party.members().size() > 1) pull(party, server, p.getUniqueId());
        });
    }

    /** Leader geht offline → Leitung an ein Online-Mitglied. */
    public void onQuit(ProxiedPlayer p) {
        UUID id = p.getUniqueId();
        plugin.db().run(con -> {
            Party party = load(con, id);
            if (party == null || !party.isLeader(id)) return null;
            UUID next = party.members().stream().filter(u -> !u.equals(id) && ProxyServer.getInstance().getPlayer(u) != null).findFirst().orElse(null);
            if (next != null) {
                Database.exec(con, "update ls_parties set leader = ? where id = ?", next.toString(), party.id());
                broadcast(party, "party.promoted", "player", party.names().get(next));
            }
            return null;
        });
    }

    /** Parties ohne Online-Mitglied & abgelaufene Einladungen entfernen. */
    public void cleanup() {
        plugin.db().run(con -> {
            Database.exec(con, "delete from ls_party_invites where expires < ?", System.currentTimeMillis());
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("select id from ls_parties where created < ?")) {
                ps.setLong(1, System.currentTimeMillis() - 10 * 60_000L);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getString(1));
                }
            }
            for (String id : ids) {
                boolean anyOnline = false;
                try (PreparedStatement ps = con.prepareStatement("select uuid from ls_party_members where party_id = ?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next() && !anyOnline) anyOnline = ProxyServer.getInstance().getPlayer(UUID.fromString(rs.getString(1))) != null;
                    }
                }
                if (!anyOnline) disband(con, id);
            }
            Database.exec(con, "delete from ls_party_members where party_id not in (select id from ls_parties)");
            return null;
        });
    }

    // ================================================================ Befehle

    public static void register(BungeeSystem plugin) {
        plugin.command("party", n -> new PartyCmd(plugin, n));
        plugin.command("partychat", n -> new PartyChatCmd(plugin, n));
    }

    static final class PartyCmd extends Cmd {
        PartyCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            PartyManager pm = plugin.parties();
            String sub = args.length == 0 ? "list" : args[0].toLowerCase();
            String arg = args.length > 1 ? args[1] : null;
            switch (sub) {
                case "invite", "einladen" -> {
                    if (arg == null) m().send(p, "party.help", "cmd", getName());
                    else pm.invite(p, arg);
                }
                case "accept", "annehmen" -> {
                    if (arg == null) m().send(p, "party.help", "cmd", getName());
                    else pm.accept(p, arg);
                }
                case "deny", "ablehnen" -> {
                    if (arg == null) m().send(p, "party.help", "cmd", getName());
                    else pm.deny(p, arg);
                }
                case "leave", "verlassen" -> pm.leave(p);
                case "kick" -> {
                    if (arg == null) m().send(p, "party.help", "cmd", getName());
                    else pm.kick(p, arg);
                }
                case "promote", "leader" -> {
                    if (arg == null) m().send(p, "party.help", "cmd", getName());
                    else pm.promote(p, arg);
                }
                case "disband", "auflösen" -> pm.disband(p);
                case "list", "info" -> pm.list(p);
                case "warp" -> pm.warp(p);
                case "join", "jump" -> pm.join(p);
                case "chat" -> {
                    if (args.length < 2) m().send(p, "party.help", "cmd", getName());
                    else pm.chat(p, join(args, 1));
                }
                case "toggle" -> plugin.friends().toggle(p, "party", "party.toggle");
                case "help", "hilfe" -> m().send(p, "party.help", "cmd", getName());
                default -> {
                    if (args.length == 1) pm.invite(p, args[0]);
                    else m().send(p, "party.help", "cmd", getName());
                }
            }
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return filter(args[0], "invite", "accept", "deny", "leave", "kick", "promote", "disband", "list", "warp", "join", "chat", "toggle", "help");
            if (args.length == 2) return players(args[1]);
            return List.of();
        }
    }

    static final class PartyChatCmd extends Cmd {
        PartyChatCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            if (args.length == 0) m().send(p, "party.help", "cmd", plugin.commandName("party"));
            else plugin.parties().chat(p, join(args, 0));
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            return List.of();
        }
    }
}
