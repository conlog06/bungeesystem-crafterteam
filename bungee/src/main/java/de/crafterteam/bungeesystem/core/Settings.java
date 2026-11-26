package de.crafterteam.bungeesystem.core;

import de.crafterteam.bungeesystem.BungeeSystem;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laufzeit-Einstellungen in bs_settings (Wartung, MOTD, Whitelist …) – geteilt zwischen mehreren Proxys,
 * Cache wird jede Minute aus der DB aktualisiert.
 */
public final class Settings {

    private final BungeeSystem plugin;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public Settings(BungeeSystem plugin) {
        this.plugin = plugin;
    }

    public void load() {
        try {
            plugin.db().submit(con -> {
                Map<String, String> m = new ConcurrentHashMap<>();
                try (PreparedStatement ps = con.prepareStatement("select k, v from bs_settings"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) m.put(rs.getString(1), rs.getString(2));
                }
                cache.clear();
                cache.putAll(m);
                return null;
            }).join();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Einstellungen nicht geladen: " + e.getMessage());
        }
    }

    public void reloadAsync() {
        plugin.getProxy().getScheduler().runAsync(plugin, this::load);
    }

    public String get(String key, String def) {
        return cache.getOrDefault(key, def);
    }

    public boolean bool(String key) {
        return "true".equals(cache.get(key));
    }

    public long number(String key, long def) {
        try {
            return Long.parseLong(cache.getOrDefault(key, Long.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void set(String key, String value) {
        if (value == null) {
            cache.remove(key);
            plugin.db().run(con -> Database.exec(con, "delete from bs_settings where k = ?", key));
            return;
        }
        cache.put(key, value);
        plugin.db().run(con -> Database.exec(con, "insert into bs_settings (k, v) values (?, ?) on duplicate key update v = values(v)", key, value));
    }
}
