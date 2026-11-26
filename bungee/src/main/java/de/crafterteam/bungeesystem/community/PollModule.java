package de.crafterteam.bungeesystem.community;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Messages;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.scheduler.ScheduledTask;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** /poll create <Sekunden> <Frage> · close · ja · nein · info – netzwerkweite Umfrage mit Klick-Buttons. */
public final class PollModule {

    private final BungeeSystem plugin;
    private volatile String question, creator;
    private volatile long ends;
    private final Map<UUID, Boolean> votes = new ConcurrentHashMap<>();
    private ScheduledTask task;

    public PollModule(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.command("poll", n -> new PollCmd(plugin, n));
    }

    private Messages m() {
        return plugin.messages();
    }

    private long yes() {
        return votes.values().stream().filter(b -> b).count();
    }

    private long no() {
        return votes.size() - yes();
    }

    private void broadcast(String key, String... ph) {
        String text = m().raw(key, ph);
        for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) Messages.sendRaw(p, text);
    }

    private synchronized void start(CommandSender s, int seconds, String q) {
        question = q;
        creator = s instanceof ProxiedPlayer p ? p.getName() : "Konsole";
        ends = System.currentTimeMillis() + seconds * 1000L;
        votes.clear();
        broadcast("poll.started", "question", q, "player", creator, "seconds", Integer.toString(seconds), "cmd", plugin.commandName("poll"));
        task = ProxyServer.getInstance().getScheduler().schedule(plugin, this::finish, seconds, TimeUnit.SECONDS);
    }

    private synchronized void finish() {
        if (question == null) return;
        long y = yes(), n = no(), total = Math.max(1, y + n);
        broadcast("poll.result", "question", question, "yes", Long.toString(y), "no", Long.toString(n),
                "yes_percent", Long.toString(y * 100 / total), "no_percent", Long.toString(n * 100 / total),
                "winner", m().raw(y > n ? "poll.winner-yes" : n > y ? "poll.winner-no" : "poll.winner-draw"));
        question = null;
        if (task != null) task.cancel();
        task = null;
    }

    final class PollCmd extends Cmd {
        PollCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            String sub = args.length == 0 ? "info" : args[0].toLowerCase();
            switch (sub) {
                case "create" -> {
                    if (!s.hasPermission("bungeesystem.poll.create")) {
                        m().send(s, "general.no-permission");
                        return;
                    }
                    if (question != null) {
                        m().send(s, "poll.running");
                        return;
                    }
                    int sec;
                    try {
                        sec = Integer.parseInt(args.length > 1 ? args[1] : "");
                    } catch (NumberFormatException e) {
                        m().send(s, "poll.usage", "cmd", getName());
                        return;
                    }
                    if (args.length < 3 || sec < 10 || sec > 3600) {
                        m().send(s, "poll.usage", "cmd", getName());
                        return;
                    }
                    start(s, sec, join(args, 2));
                }
                case "close" -> {
                    if (!s.hasPermission("bungeesystem.poll.close")) {
                        m().send(s, "general.no-permission");
                        return;
                    }
                    if (question == null) m().send(s, "poll.none");
                    else finish();
                }
                case "yes", "ja", "no", "nein" -> {
                    ProxiedPlayer p = player(s);
                    if (p == null) return;
                    if (question == null) {
                        m().send(p, "poll.none");
                        return;
                    }
                    if (!p.hasPermission("bungeesystem.poll.vote") && plugin.config().getBoolean("poll.vote-permission", false)) {
                        m().send(p, "general.no-permission");
                        return;
                    }
                    boolean yes = sub.equals("yes") || sub.equals("ja");
                    Boolean before = votes.put(p.getUniqueId(), yes);
                    m().send(p, before == null ? "poll.voted" : "poll.changed", "vote", m().raw(yes ? "poll.yes" : "poll.no"));
                }
                default -> {
                    if (question == null) {
                        m().send(s, "poll.none");
                        if (s.hasPermission("bungeesystem.poll.create")) m().send(s, "poll.usage", "cmd", getName());
                        return;
                    }
                    m().send(s, "poll.info", "question", question, "player", creator, "yes", Long.toString(yes()), "no", Long.toString(no()),
                            "seconds", Long.toString(Math.max(0, (ends - System.currentTimeMillis()) / 1000)), "cmd", getName());
                }
            }
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return s.hasPermission("bungeesystem.poll.create")
                    ? filter(args[0], "create", "close", "ja", "nein", "info") : filter(args[0], "ja", "nein", "info");
            if (args.length == 2 && args[0].equalsIgnoreCase("create")) return filter(args[1], "60", "120", "300");
            return List.of();
        }
    }
}
