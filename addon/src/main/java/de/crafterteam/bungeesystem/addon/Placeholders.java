package de.crafterteam.bungeesystem.addon;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** %bungeesystem_clantag%, %bungeesystem_clantag_formatted%, %bungeesystem_onlinetime%, %bungeesystem_onlinetime_hours% */
final class Placeholders extends PlaceholderExpansion {

    private final BungeeSystemAddon addon;

    Placeholders(BungeeSystemAddon addon) {
        this.addon = addon;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "bungeesystem";
    }

    @Override
    public @NotNull String getAuthor() {
        return "SujucDerBreite";
    }

    @Override
    public @NotNull String getVersion() {
        return addon.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        String tag = addon.clanTag(player.getUniqueId());
        long ms = addon.onlineTime(player.getUniqueId());
        return switch (params.toLowerCase()) {
            case "clantag" -> tag == null ? "" : tag;
            case "clantag_formatted" -> tag == null ? "" : "§8[§e" + tag + "§8] ";
            case "onlinetime" -> format(ms);
            case "onlinetime_hours" -> Long.toString(ms / 3_600_000L);
            default -> null;
        };
    }

    static String format(long ms) {
        long m = ms / 60_000, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        return m + "m";
    }
}
