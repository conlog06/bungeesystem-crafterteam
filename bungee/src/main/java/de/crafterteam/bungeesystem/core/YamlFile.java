package de.crafterteam.bungeesystem.core;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Level;

/** YAML-Datei im Plugin-Ordner – fehlende Schlüssel werden aus der Standard-Datei im JAR ergänzt. */
public final class YamlFile {

    private final Plugin plugin;
    private final String name;
    private Configuration config;

    public YamlFile(Plugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public Configuration get() {
        return config;
    }

    public void save() {
        try {
            ConfigurationProvider.getProvider(YamlConfiguration.class).save(config, new File(plugin.getDataFolder(), name));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Konnte " + name + " nicht speichern", e);
        }
    }

    public void load() {
        File folder = plugin.getDataFolder();
        if (!folder.exists()) folder.mkdirs();
        File file = new File(folder, name);
        ConfigurationProvider yaml = ConfigurationProvider.getProvider(YamlConfiguration.class);
        try {
            if (!file.exists()) {
                try (InputStream in = plugin.getResourceAsStream(name)) {
                    if (in != null) Files.copy(in, file.toPath());
                    else file.createNewFile();
                }
            }
            config = yaml.load(file);
            Configuration defaults;
            try (InputStream in = plugin.getResourceAsStream(name)) {
                defaults = in == null ? null : yaml.load(in);
            }
            if (defaults != null && merge(defaults, config, "")) {
                yaml.save(config, file);
                plugin.getLogger().info(name + ": neue Einträge ergänzt.");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Konnte " + name + " nicht laden!", e);
            if (config == null) config = new Configuration();
        }
    }

    /** Abschnitte, deren Einträge der Admin bewusst löschen darf (werden nicht wieder ergänzt). */
    private static final java.util.Set<String> USER_SECTIONS = java.util.Set.of(
            "punish.reasons", "report.reasons", "chat.format.groups", "server-commands", "links", "help.pages");

    private static boolean merge(Configuration from, Configuration into, String path) {
        boolean changed = false;
        for (String key : from.getKeys()) {
            if (USER_SECTIONS.contains(path + key) && into.contains(key)) continue;
            Object value = from.get(key);
            if (value instanceof Configuration section) {
                Configuration target = into.getSection(key);
                if (!into.contains(key)) {
                    into.set(key, section);
                    changed = true;
                } else if (merge(section, target, path + key + ".")) {
                    changed = true;
                }
            } else if (!into.contains(key)) {
                into.set(key, value);
                changed = true;
            }
        }
        return changed;
    }
}
