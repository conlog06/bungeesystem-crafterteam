package de.crafterteam.bungeesystem.punish;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.core.PlayerManager.Known;
import de.crafterteam.bungeesystem.punish.PunishManager.Punishment;
import de.crafterteam.bungeesystem.punish.PunishManager.Reason;
import de.crafterteam.bungeesystem.punish.PunishManager.Type;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Alle Befehle des Ban-/Mute-Moduls. */
public final class PunishCommands {

    private PunishCommands() {
    }

    public static void register(BungeeSystem plugin) {
        plugin.command("ban", n -> new PunishCmd(plugin, n, Type.BAN));
        plugin.command("mute", n -> new PunishCmd(plugin, n, Type.MUTE));
        plugin.command("unban", n -> new UnCmd(plugin, n, Type.BAN));
        plugin.command("unmute", n -> new UnCmd(plugin, n, Type.MUTE));
        plugin.command("kick", n -> new KickCmd(plugin, n));
        plugin.command("check", n -> new CheckCmd(plugin, n));
        plugin.command("history", n -> new HistoryCmd(plugin, n));
        plugin.command("punish", n -> new PunishGuiCmd(plugin, n));
        plugin.command("banreasons", n -> new ReasonsCmd(plugin, n));
    }

    /** Spieler auflösen, sonst "nicht gefunden". */
    static void resolve(BungeeSystem plugin, CommandSender s, String name, Consumer<Known> then) {
        plugin.players().lookup(name).thenAccept((Optional<Known> k) -> {
            if (k.isEmpty()) plugin.messages().send(s, "general.player-not-found", "player", name);
            else then.accept(k.get());
        }).exceptionally(ex -> {
            plugin.messages().send(s, "general.error");
            return null;
        });
    }

    static boolean isProtected(CommandSender s, Known target) {
        ProxiedPlayer p = ProxyServer.getInstance().getPlayer(target.uuid());
        return p != null && p.hasPermission("bungeesystem.punish.protected") && !s.hasPermission("bungeesystem.punish.protected.bypass");
    }

    /** Liste der Vorlagen als klickbare Buttons. */
    static void sendReasons(BungeeSystem plugin, CommandSender s, String target, Type type) {
        Messages m = plugin.messages();
        m.send(s, "punish.reasons-header", "player", target == null ? "<Spieler>" : target);
        String cmd = type == Type.BAN ? "ban" : "mute";
        for (Reason r : plugin.punish().reasons(type)) {
            if (!plugin.punish().mayUse(s, r)) continue;
            String command = target == null ? "suggest:/" + plugin.commandName(cmd) + " <Spieler> " + r.id()
                    : "/" + plugin.commandName(cmd) + " " + target + " " + r.id();
            String line = m.raw("punish.reasons-entry", "id", r.id(), "name", r.name(), "duration", r.durationText(),
                    "type", r.type() == Type.BAN ? "Ban" : "Mute", "command", command);
            Messages.sendRaw(s, line);
        }
    }

    // ================================================================ /ban, /mute

    static final class PunishCmd extends Cmd {
        private final Type type;
        private final String key;

        PunishCmd(BungeeSystem plugin, String[] names, Type type) {
            super(plugin, names, type == Type.BAN ? "bungeesystem.ban" : "bungeesystem.mute");
            this.type = type;
            this.key = type == Type.BAN ? "ban" : "mute";
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length == 0) {
                m().send(s, key + ".usage", "cmd", getName());
                return;
            }
            if (args.length == 1) {
                // nur Spieler → GUI (Addon) oder Gründe-Liste im Chat
                if (s instanceof ProxiedPlayer p && plugin.bridge().hasAddon(p)) {
                    resolve(plugin, s, args[0], t -> plugin.guis().openPunish(p, t));
                } else {
                    sendReasons(plugin, s, args[0], type);
                }
                return;
            }
            String target = args[0];
            Reason tpl = plugin.punish().reason(args[1], type);
            Reason any = tpl == null ? plugin.punish().reason(args[1], null) : null;
            if (tpl == null && any != null && args.length == 2) {
                // ID gehört zum anderen Typ (z. B. /ban Name 5 wobei 5 ein Mute ist)
                m().send(s, "punish.wrong-type", "id", any.id(), "name", any.name());
                return;
            }
            long duration;
            String custom;
            if (tpl != null) {
                if (!plugin.punish().mayUse(s, tpl)) {
                    m().send(s, "general.no-permission");
                    return;
                }
                duration = tpl.duration();
                custom = null;
            } else {
                if (!s.hasPermission("bungeesystem." + key + ".custom")) {
                    m().send(s, "punish.unknown-reason", "id", args[1]);
                    sendReasons(plugin, s, target, type);
                    return;
                }
                long d = Time.parse(args[1]);
                if (d != -2) {
                    if (args.length < 3) {
                        m().send(s, key + ".usage", "cmd", getName());
                        return;
                    }
                    duration = d;
                    custom = join(args, 2);
                } else {
                    duration = type == Type.BAN ? -1 : Time.parse(plugin.config().getString("punish.custom-mute-duration", "1d"));
                    custom = join(args, 1);
                }
                if (duration < 0 && !s.hasPermission("bungeesystem." + key + ".permanent")) {
                    m().send(s, "punish.no-permanent");
                    return;
                }
                if (custom.length() > 120) custom = custom.substring(0, 120);
            }
            String customReason = custom;
            resolve(plugin, s, target, t -> {
                if (s instanceof ProxiedPlayer p && p.getUniqueId().equals(t.uuid())) {
                    m().send(s, "punish.self");
                    return;
                }
                if (isProtected(s, t)) {
                    m().send(s, "punish.protected", "player", t.name());
                    return;
                }
                if (type == Type.BAN && plugin.punish().ban(t.uuid()) != null && !s.hasPermission("bungeesystem.ban.override")) {
                    m().send(s, "ban.already", "player", t.name());
                    return;
                }
                plugin.punish().punish(s, t, type, tpl, customReason, duration).thenAccept(p ->
                        m().send(s, key + ".success", "player", p.name(), "reason", p.reason(), "duration",
                                p.permanent() ? "Permanent" : Time.format(p.until() - p.created()), "id", "#" + p.id()));
            });
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return players(args[0]);
            if (args.length == 2) {
                List<String> out = new ArrayList<>();
                for (Reason r : plugin.punish().reasons(type)) if (plugin.punish().mayUse(s, r)) out.add(r.id());
                if (s.hasPermission("bungeesystem." + key + ".custom")) out.addAll(List.of("1h", "1d", "7d", "30d", "perm"));
                return filter(args[1], out);
            }
            return List.of();
        }
    }

    // ================================================================ /unban, /unmute

    static final class UnCmd extends Cmd {
        private final Type type;

        UnCmd(BungeeSystem plugin, String[] names, Type type) {
            super(plugin, names, type == Type.BAN ? "bungeesystem.unban" : "bungeesystem.unmute");
            this.type = type;
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            String key = type == Type.BAN ? "unban" : "unmute";
            if (args.length == 0) {
                m().send(s, key + ".usage", "cmd", getName());
                return;
            }
            resolve(plugin, s, args[0], t -> {
                var f = type == Type.BAN ? plugin.punish().unban(t.uuid(), name(s)) : plugin.punish().unmute(t.uuid(), name(s));
                f.thenAccept(ok -> {
                    if (!ok) {
                        m().send(s, key + ".not", "player", t.name());
                        return;
                    }
                    m().send(s, key + ".success", "player", t.name());
                    plugin.notifier().notify(type == Type.BAN ? "ban" : "mute", key + ".notify", "player", t.name(), "staff", name(s),
                            "reason", join(args, 1));
                    if (type == Type.MUTE) {
                        ProxiedPlayer online = ProxyServer.getInstance().getPlayer(t.uuid());
                        if (online != null) m().send(online, "unmute.target");
                    }
                });
            });
        }
    }

    // ================================================================ /kick

    static final class KickCmd extends Cmd {
        KickCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.kick");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length == 0) {
                m().send(s, "kick.usage", "cmd", getName());
                return;
            }
            ProxiedPlayer t = ProxyServer.getInstance().getPlayer(args[0]);
            if (t == null) {
                m().send(s, "general.player-offline", "player", args[0]);
                return;
            }
            if (t.hasPermission("bungeesystem.punish.protected") && !s.hasPermission("bungeesystem.punish.protected.bypass")) {
                m().send(s, "punish.protected", "player", t.getName());
                return;
            }
            String reason = args.length > 1 ? join(args, 1) : m().raw("kick.no-reason");
            plugin.punish().kick(s, t, reason);
            m().send(s, "kick.success", "player", t.getName(), "reason", reason);
        }
    }

    // ================================================================ /check

    static final class CheckCmd extends Cmd {
        CheckCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.check");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length == 0) {
                m().send(s, "check.usage", "cmd", getName());
                return;
            }
            resolve(plugin, s, args[0], t -> {
                Punishment ban = plugin.punish().ban(t.uuid());
                Punishment mute = plugin.punish().mute(t.uuid());
                ProxiedPlayer online = ProxyServer.getInstance().getPlayer(t.uuid());
                plugin.players().joinInfo(t.uuid()).thenAccept(info -> {
                    long ot = info[2];
                    var session = plugin.players().session(t.uuid());
                    if (session != null) ot = session.onlineTime();
                    m().send(s, "check.header", "player", t.name());
                    m().send(s, "check.status", "status", online != null
                            ? m().raw("check.online", "server", online.getServer() == null ? "?" : online.getServer().getInfo().getName())
                            : m().raw("check.offline", "date", Time.date(info[1])));
                    m().send(s, "check.first-join", "date", Time.date(info[0]));
                    m().send(s, "check.onlinetime", "time", Time.hours(ot));
                    if (ban != null) m().send(s, "check.banned", "reason", ban.reason(), "staff", ban.staff(), "remaining", ban.remaining(), "id", "#" + ban.id());
                    else m().send(s, "check.not-banned");
                    if (mute != null) m().send(s, "check.muted", "reason", mute.reason(), "staff", mute.staff(), "remaining", mute.remaining(), "id", "#" + mute.id());
                    else m().send(s, "check.not-muted");
                    if (s.hasPermission("bungeesystem.check.ip")) {
                        String ip = online != null ? de.crafterteam.bungeesystem.core.PlayerManager.ip(online) : t.ip();
                        plugin.players().alts(ip, t.uuid()).thenAccept(alts -> {
                            m().send(s, "check.ip", "ip", ip == null || ip.isBlank() ? "-" : ip);
                            m().send(s, "check.alts", "alts", alts.isEmpty() ? "-" : String.join(", ", alts));
                        });
                    }
                });
            });
        }
    }

    // ================================================================ /history

    static final class HistoryCmd extends Cmd {
        HistoryCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.history");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            if (args.length == 0) {
                m().send(s, "history.usage", "cmd", getName());
                return;
            }
            resolve(plugin, s, args[0], t -> plugin.punish().history(t.uuid(), Type.BAN)
                    .thenCombine(plugin.punish().history(t.uuid(), Type.MUTE), (b, mu) -> {
                        List<Punishment> all = new ArrayList<>(b);
                        all.addAll(mu);
                        all.sort((x, y) -> Long.compare(y.created(), x.created()));
                        return all;
                    }).thenCombine(plugin.punish().kicks(t.uuid()), (all, kicks) -> {
                        m().send(s, "history.header", "player", t.name(), "count", Integer.toString(all.size() + kicks.size()));
                        if (all.isEmpty() && kicks.isEmpty()) m().send(s, "history.empty");
                        for (Punishment p : all) {
                            String state = p.active() && !p.expired() ? m().raw("history.state-active")
                                    : p.removedBy() != null && !p.removedBy().isBlank() ? m().raw("history.state-removed", "staff", p.removedBy())
                                    : m().raw("history.state-expired");
                            m().send(s, "history.entry", "type", p.type() == Type.BAN ? "Ban" : "Mute", "id", "#" + p.id(), "reason", p.reason(),
                                    "staff", p.staff(), "date", Time.date(p.created()), "duration", p.permanent() ? "Permanent" : Time.format(p.until() - p.created()),
                                    "state", state);
                        }
                        for (PunishManager.Kick k : kicks) {
                            m().send(s, "history.kick", "reason", k.reason(), "staff", k.staff(), "date", Time.date(k.at()));
                        }
                        return null;
                    }));
        }
    }

    // ================================================================ /punish (GUI)

    static final class PunishGuiCmd extends Cmd {
        PunishGuiCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.punish.gui");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            if (args.length == 0) {
                m().send(s, "punish.gui-usage", "cmd", getName());
                return;
            }
            if (!plugin.bridge().hasAddon(p)) {
                m().send(s, "general.no-addon");
                if (p.hasPermission("bungeesystem.ban")) sendReasons(plugin, s, args[0], Type.BAN);
                if (p.hasPermission("bungeesystem.mute")) sendReasons(plugin, s, args[0], Type.MUTE);
                return;
            }
            resolve(plugin, s, args[0], t -> plugin.guis().openPunish(p, t));
        }
    }

    // ================================================================ /banreasons

    static final class ReasonsCmd extends Cmd {
        ReasonsCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, "bungeesystem.ban");
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            sendReasons(plugin, s, args.length > 0 ? args[0] : null, Type.BAN);
            if (s.hasPermission("bungeesystem.mute")) sendReasons(plugin, s, args.length > 0 ? args[0] : null, Type.MUTE);
        }
    }
}
