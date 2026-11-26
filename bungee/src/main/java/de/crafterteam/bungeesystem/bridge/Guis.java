package de.crafterteam.bungeesystem.bridge;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.core.PlayerManager.Known;
import de.crafterteam.bungeesystem.punish.PunishManager;
import de.crafterteam.bungeesystem.punish.PunishManager.Reason;
import de.crafterteam.bungeesystem.report.ReportManager.Report;
import de.crafterteam.bungeesystem.util.Text;
import de.crafterteam.bungeesystem.util.Time;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.List;

/**
 * Baut die Inventar-GUIs, die das Paper-Addon anzeigt. Der Proxy schickt fertige Einträge
 * (Material, Name, Lore, Befehl pro Klickart) – das Addon ist dadurch "dumm" und braucht keine eigene Config.
 */
public final class Guis {

    /** Ein Slot der GUI. */
    public record Item(int slot, String material, String name, List<String> lore, String left, String right, String shift, String skullOwner) {
    }

    private final BungeeSystem plugin;

    public Guis(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    private String t(String key, String... ph) {
        return Text.color(plugin.messages().raw(key, ph));
    }

    private List<String> lines(String key, String... ph) {
        return List.of(t(key, ph).split("\n"));
    }

    public void send(ProxiedPlayer p, String title, int rows, List<Item> items) {
        plugin.bridge().send(p, "GUI", out -> {
            out.writeUTF(p.getUniqueId().toString());
            out.writeUTF(title);
            out.writeInt(rows);
            out.writeInt(items.size());
            for (Item i : items) {
                out.writeInt(i.slot());
                out.writeUTF(i.material());
                out.writeUTF(i.name());
                out.writeInt(i.lore().size());
                for (String l : i.lore()) out.writeUTF(l);
                out.writeUTF(i.left() == null ? "" : i.left());
                out.writeUTF(i.right() == null ? "" : i.right());
                out.writeUTF(i.shift() == null ? "" : i.shift());
                out.writeUTF(i.skullOwner() == null ? "" : i.skullOwner());
            }
        });
    }

    // ---------------------------------------------------------------- Straf-GUI

    public void openPunish(ProxiedPlayer staff, Known target) {
        PunishManager pm = plugin.punish();
        PunishManager.Punishment ban = pm.ban(target.uuid()), mute = pm.mute(target.uuid());
        java.util.ArrayList<Item> items = new java.util.ArrayList<>();
        String ban_ = plugin.commandName("ban"), mute_ = plugin.commandName("mute");
        items.add(new Item(4, "PLAYER_HEAD", t("gui.punish.head", "player", target.name()),
                lines("gui.punish.head-lore", "ban", ban == null ? t("general.no") : ban.reason() + " (" + ban.remaining() + ")",
                        "mute", mute == null ? t("general.no") : mute.reason() + " (" + mute.remaining() + ")",
                        "online", ProxyServer.getInstance().getPlayer(target.uuid()) != null ? t("general.yes") : t("general.no")),
                plugin.commandName("check") + " " + target.name(), plugin.commandName("history") + " " + target.name(), null, target.name()));
        int slot = 18;
        for (Reason r : pm.allReasons()) {
            if (slot >= 45) break;
            boolean isBan = r.type() == PunishManager.Type.BAN;
            if (!staff.hasPermission(isBan ? "bungeesystem.ban" : "bungeesystem.mute") || !pm.mayUse(staff, r)) continue;
            items.add(new Item(slot++, r.item(), t("gui.punish.reason", "id", r.id(), "name", r.name()),
                    lines("gui.punish.reason-lore", "type", isBan ? "Ban" : "Mute", "duration", r.durationText()),
                    (isBan ? ban_ : mute_) + " " + target.name() + " " + r.id(), null, null, null));
        }
        if (ban != null && staff.hasPermission("bungeesystem.unban")) {
            items.add(new Item(48, "LIME_DYE", t("gui.punish.unban"), lines("gui.punish.unban-lore", "reason", ban.reason()),
                    plugin.commandName("unban") + " " + target.name(), null, null, null));
        }
        if (mute != null && staff.hasPermission("bungeesystem.unmute")) {
            items.add(new Item(50, "LIGHT_BLUE_DYE", t("gui.punish.unmute"), lines("gui.punish.unmute-lore", "reason", mute.reason()),
                    plugin.commandName("unmute") + " " + target.name(), null, null, null));
        }
        if (ProxyServer.getInstance().getPlayer(target.uuid()) != null && staff.hasPermission("bungeesystem.kick")) {
            items.add(new Item(49, "IRON_DOOR", t("gui.punish.kick"), lines("gui.punish.kick-lore"),
                    plugin.commandName("kick") + " " + target.name(), null, null, null));
        }
        send(staff, t("gui.punish.title", "player", target.name()), 6, items);
    }

    // ---------------------------------------------------------------- Report-GUI

    public void openReports(ProxiedPlayer staff, List<Report> reports) {
        java.util.ArrayList<Item> items = new java.util.ArrayList<>();
        String rep = plugin.commandName("report");
        int slot = 0;
        for (Report r : reports) {
            if (slot >= 45) break;
            ProxiedPlayer target = ProxyServer.getInstance().getPlayer(r.targetName());
            String status = t(r.status().equals("OPEN") ? "gui.reports.open" : "gui.reports.progress", "handler", r.handler());
            items.add(new Item(slot++, "PLAYER_HEAD", t("gui.reports.entry", "id", Integer.toString(r.id()), "player", r.targetName()),
                    lines("gui.reports.entry-lore", "reason", r.reason(), "reporter", r.reporterName(), "server", r.server(),
                            "date", Time.date(r.created()), "status", status,
                            "online", target != null ? t("gui.reports.target-online", "server", target.getServer() == null ? "?" : target.getServer().getInfo().getName())
                                    : t("gui.reports.target-offline")),
                    rep + " accept " + r.id(), rep + " close " + r.id(), plugin.commandName("punish") + " " + r.targetName(), r.targetName()));
        }
        if (reports.isEmpty()) {
            items.add(new Item(22, "LIME_STAINED_GLASS_PANE", t("gui.reports.empty"), List.of(), null, null, null, null));
        }
        items.add(new Item(49, "CLOCK", t("gui.reports.refresh"), lines("gui.reports.refresh-lore", "count", Integer.toString(reports.size())),
                plugin.commandName("reports"), null, null, null));
        send(staff, t("gui.reports.title", "count", Integer.toString(reports.size())), 6, items);
    }
}
