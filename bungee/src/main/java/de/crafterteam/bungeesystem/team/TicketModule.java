package de.crafterteam.bungeesystem.team;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.command.Cmd;
import de.crafterteam.bungeesystem.core.Database;
import de.crafterteam.bungeesystem.core.Messages;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Ticket-System: Spieler erstellen Tickets, das Team antwortet – mit Verlauf in der Datenbank, auch wenn der Spieler offline ist.
 * Status: OPEN (wartet aufs Team) → ANSWERED (Team hat geantwortet) → CLOSED.
 */
public final class TicketModule {

    record Ticket(int id, UUID uuid, String name, String subject, String status, String assignee, long created, long updated) {
    }

    record Entry(String author, boolean staff, String message, long created) {
    }

    private final BungeeSystem plugin;

    public TicketModule(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.command("ticket", n -> new TicketCmd(plugin, n));
    }

    private Messages m() {
        return plugin.messages();
    }

    private String cmd() {
        return plugin.commandName("ticket");
    }

    private static Ticket ticket(ResultSet rs) throws java.sql.SQLException {
        return new Ticket(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getLong(7), rs.getLong(8));
    }

    private static final String COLS = "id, uuid, player_name, subject, status, assignee, created, updated";

    private CompletableFuture<Ticket> get(int id) {
        return plugin.db().submit(con -> {
            try (PreparedStatement ps = con.prepareStatement("select " + COLS + " from bs_tickets where id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? ticket(rs) : null;
                }
            }
        });
    }

    private void addMessage(int id, String author, boolean staff, String message) {
        long now = System.currentTimeMillis();
        plugin.db().run(con -> {
            Database.exec(con, "insert into bs_ticket_messages (ticket_id, author, staff, message, created) values (?, ?, ?, ?, ?)", id, author, staff, message, now);
            Database.exec(con, "update bs_tickets set updated = ?, status = ? where id = ?", now, staff ? "ANSWERED" : "OPEN", id);
            return null;
        });
    }

    private void notifyStaff(String key, String... ph) {
        String text = m().raw(key, ph);
        for (ProxiedPlayer x : ProxyServer.getInstance().getPlayers()) {
            if (x.hasPermission("bungeesystem.ticket.manage") && plugin.notifier().receives(x, "report")) Messages.sendRaw(x, text);
        }
    }

    /** Beim Join: beantwortete Tickets anzeigen bzw. Team an offene Tickets erinnern. */
    public void remind(ProxiedPlayer p) {
        plugin.db().submit(con -> new long[]{
                Database.longValue(con, "select count(*) from bs_tickets where uuid = ? and status = 'ANSWERED'", 0, p.getUniqueId().toString()),
                p.hasPermission("bungeesystem.ticket.manage") ? Database.longValue(con, "select count(*) from bs_tickets where status = 'OPEN'", 0) : 0
        }).thenAccept(v -> {
            if (v[0] > 0) m().send(p, "ticket.reminder-player", "count", Long.toString(v[0]), "cmd", cmd());
            if (v[1] > 0) m().send(p, "ticket.reminder-staff", "count", Long.toString(v[1]), "cmd", cmd());
        });
    }

    final class TicketCmd extends Cmd {
        TicketCmd(BungeeSystem plugin, String[] names) {
            super(plugin, names, null);
        }

        @Override
        protected void run(CommandSender s, String[] args) {
            ProxiedPlayer p = player(s);
            if (p == null) return;
            boolean staff = p.hasPermission("bungeesystem.ticket.manage");
            String sub = args.length == 0 ? "list" : args[0].toLowerCase();
            switch (sub) {
                case "create", "neu" -> create(p, join(args, 1));
                case "list", "liste" -> list(p, staff && args.length > 1 && args[1].equalsIgnoreCase("all"));
                case "view", "show", "ansehen" -> withId(p, args, t -> view(p, t, staff));
                case "reply", "antworten" -> withId(p, args, t -> reply(p, t, staff, join(args, 2)));
                case "close", "schließen" -> withId(p, args, t -> close(p, t, staff));
                case "claim" -> {
                    if (!staff) {
                        m().send(p, "general.no-permission");
                        return;
                    }
                    withId(p, args, t -> plugin.db().run(con -> {
                        Database.exec(con, "update bs_tickets set assignee = ? where id = ?", p.getName(), t.id());
                        m().send(p, "ticket.claimed", "id", Integer.toString(t.id()));
                        return null;
                    }));
                }
                default -> m().send(p, staff ? "ticket.help-staff" : "ticket.help", "cmd", getName());
            }
        }

        private void withId(ProxiedPlayer p, String[] args, java.util.function.Consumer<Ticket> then) {
            int id;
            try {
                id = Integer.parseInt(args.length > 1 ? args[1].replace("#", "") : "");
            } catch (NumberFormatException e) {
                m().send(p, "ticket.help", "cmd", getName());
                return;
            }
            get(id).thenAccept(t -> {
                boolean staff = p.hasPermission("bungeesystem.ticket.manage");
                if (t == null || !staff && !t.uuid().equals(p.getUniqueId())) m().send(p, "ticket.not-found", "id", Integer.toString(id));
                else then.accept(t);
            });
        }

        @Override
        protected List<String> complete(CommandSender s, String[] args) {
            if (args.length == 1) return s.hasPermission("bungeesystem.ticket.manage")
                    ? filter(args[0], "create", "list", "view", "reply", "close", "claim") : filter(args[0], "create", "list", "view", "reply", "close");
            return List.of();
        }
    }

    private void create(ProxiedPlayer p, String text) {
        if (text.isBlank() || text.length() < 5) {
            m().send(p, "ticket.too-short");
            return;
        }
        String subject = text.length() > 120 ? text.substring(0, 120) : text;
        int max = plugin.config().getInt("tickets.max-open", 3);
        plugin.db().submit(con -> {
            if (Database.longValue(con, "select count(*) from bs_tickets where uuid = ? and status <> 'CLOSED'", 0, p.getUniqueId().toString()) >= max) return -1;
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = con.prepareStatement("insert into bs_tickets (uuid, player_name, subject, status, assignee, created, updated) "
                    + "values (?, ?, ?, 'OPEN', '', ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, p.getUniqueId().toString());
                ps.setString(2, p.getName());
                ps.setString(3, subject);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    int id = rs.next() ? rs.getInt(1) : 0;
                    Database.exec(con, "insert into bs_ticket_messages (ticket_id, author, staff, message, created) values (?, ?, 0, ?, ?)", id, p.getName(), text, now);
                    return id;
                }
            }
        }).thenAccept(id -> {
            if (id < 0) {
                m().send(p, "ticket.too-many", "max", Integer.toString(max));
                return;
            }
            m().send(p, "ticket.created", "id", Integer.toString(id), "cmd", cmd());
            notifyStaff("ticket.notify-new", "id", Integer.toString(id), "player", p.getName(), "message", subject, "cmd", cmd());
            if (plugin.discord() != null) plugin.discord().onNotify("ticket", "Neues Ticket #" + id + " von " + p.getName() + ": " + subject);
        });
    }

    private void list(ProxiedPlayer p, boolean all) {
        boolean staff = p.hasPermission("bungeesystem.ticket.manage");
        plugin.db().submit(con -> {
            List<Ticket> out = new ArrayList<>();
            String sql = staff && all ? "select " + COLS + " from bs_tickets where status <> 'CLOSED' order by status = 'OPEN' desc, updated desc limit 20"
                    : "select " + COLS + " from bs_tickets where uuid = ? order by updated desc limit 10";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                if (!(staff && all)) ps.setString(1, p.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(ticket(rs));
                }
            }
            return out;
        }).thenAccept(list -> {
            m().send(p, "ticket.list-header", "count", Integer.toString(list.size()));
            if (list.isEmpty()) m().send(p, "ticket.list-empty", "cmd", cmd());
            if (staff && !all) m().send(p, "ticket.list-staff-hint", "cmd", cmd());
            for (Ticket t : list) {
                m().send(p, "ticket.list-entry", "id", Integer.toString(t.id()), "player", t.name(), "message", t.subject().length() > 40 ? t.subject().substring(0, 40) + "…" : t.subject(),
                        "status", m().raw("ticket.status-" + t.status().toLowerCase()), "date", Time.date(t.updated()), "cmd", cmd());
            }
        });
    }

    private void view(ProxiedPlayer p, Ticket t, boolean staff) {
        plugin.db().submit(con -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("select author, staff, message, created from bs_ticket_messages where ticket_id = ? order by id")) {
                ps.setInt(1, t.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Entry(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getLong(4)));
                }
            }
            return out;
        }).thenAccept(entries -> {
            m().send(p, "ticket.view-header", "id", Integer.toString(t.id()), "player", t.name(), "status", m().raw("ticket.status-" + t.status().toLowerCase()),
                    "assignee", t.assignee().isBlank() ? "-" : t.assignee());
            for (Entry e : entries) m().send(p, e.staff() ? "ticket.view-staff" : "ticket.view-player", "player", e.author(), "message", e.message(),
                    "date", Time.date(e.created()));
            if (!t.status().equals("CLOSED")) m().send(p, "ticket.view-actions", "id", Integer.toString(t.id()), "cmd", cmd());
        });
    }

    private void reply(ProxiedPlayer p, Ticket t, boolean staff, String text) {
        if (t.status().equals("CLOSED")) {
            m().send(p, "ticket.closed-already", "id", Integer.toString(t.id()));
            return;
        }
        if (text.isBlank()) {
            m().send(p, "ticket.help", "cmd", cmd());
            return;
        }
        boolean asStaff = staff && !t.uuid().equals(p.getUniqueId());
        addMessage(t.id(), p.getName(), asStaff, text);
        m().send(p, "ticket.replied", "id", Integer.toString(t.id()));
        if (asStaff) {
            ProxiedPlayer owner = ProxyServer.getInstance().getPlayer(t.uuid());
            if (owner != null) m().send(owner, "ticket.answer-received", "id", Integer.toString(t.id()), "staff", p.getName(), "message", text, "cmd", cmd());
            if (t.assignee().isBlank()) plugin.db().run(con -> Database.exec(con, "update bs_tickets set assignee = ? where id = ?", p.getName(), t.id()));
        } else {
            notifyStaff("ticket.notify-reply", "id", Integer.toString(t.id()), "player", p.getName(), "message", text, "cmd", cmd());
        }
    }

    private void close(ProxiedPlayer p, Ticket t, boolean staff) {
        plugin.db().run(con -> Database.exec(con, "update bs_tickets set status = 'CLOSED', updated = ? where id = ?", System.currentTimeMillis(), t.id()));
        m().send(p, "ticket.closed", "id", Integer.toString(t.id()));
        ProxiedPlayer owner = ProxyServer.getInstance().getPlayer(t.uuid());
        if (owner != null && owner != p) m().send(owner, "ticket.closed-by-staff", "id", Integer.toString(t.id()), "staff", p.getName());
    }
}
