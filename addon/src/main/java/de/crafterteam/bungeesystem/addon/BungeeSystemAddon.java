package de.crafterteam.bungeesystem.addon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-Gegenstück zum BungeeSystem: zeigt die Inventar-GUIs (Reports, Strafen), die der Proxy schickt,
 * und setzt den Clantag hinter den Namen in der Tab-Liste. Klicks gehen als Befehl zurück an den Proxy.
 */
public final class BungeeSystemAddon extends JavaPlugin implements Listener, PluginMessageListener {

    public static final String CHANNEL = "crafterteam:bungee";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.builder().character('&').hexColors().build();

    private final Map<UUID, String> clanTags = new ConcurrentHashMap<>();
    private final Map<UUID, Long> onlineTime = new ConcurrentHashMap<>();
    private boolean tabEnabled;

    /** Eine geöffnete GUI und was jeder Slot bei Links-/Rechts-/Shift-Klick ausführt. */
    static final class Gui implements InventoryHolder {
        final Map<Integer, String[]> actions = new HashMap<>();
        Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        tabEnabled = getConfig().getBoolean("clantag-in-tab.enabled", true);
        for (String other : getConfig().getStringList("clantag-in-tab.skip-if-plugins")) {
            if (getServer().getPluginManager().getPlugin(other) != null) {
                tabEnabled = false;
                getLogger().info(other + " gefunden – Clantag in der Tab-Liste übernimmt " + other + ".");
            }
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new Placeholders(this).register();
                getLogger().info("PlaceholderAPI: %bungeesystem_clantag%, %bungeesystem_onlinetime% registriert.");
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI-Hook fehlgeschlagen: " + t.getMessage());
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) announce(p);
    }

    public String clanTag(UUID uuid) {
        return clanTags.get(uuid);
    }

    public long onlineTime(UUID uuid) {
        return onlineTime.getOrDefault(uuid, 0L);
    }

    // ---------------------------------------------------------------- Proxy-Kommunikation

    private void announce(Player p) {
        send(p, "ADDON", out -> out.writeInt(1));
    }

    @FunctionalInterface
    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    void send(Player via, String type, Writer w) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(type);
            w.write(out);
            via.sendPluginMessage(this, CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            getLogger().warning("Senden an den Proxy fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Befehl im Namen des Spielers auf dem Proxy ausführen. */
    void run(Player p, String command) {
        send(p, "RUN", out -> {
            out.writeUTF(p.getUniqueId().toString());
            out.writeUTF(command);
        });
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player carrier, byte @NotNull [] message) {
        if (!CHANNEL.equals(channel)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String type = in.readUTF();
            switch (type) {
                case "HELLO" -> announce(carrier);
                case "CLANTAG" -> {
                    UUID uuid = UUID.fromString(in.readUTF());
                    String tag = in.readUTF();
                    if (tag.isEmpty()) clanTags.remove(uuid);
                    else clanTags.put(uuid, tag);
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) updateTab(p);
                }
                case "ONLINETIME" -> {
                    UUID uuid = UUID.fromString(in.readUTF());
                    onlineTime.put(uuid, in.readLong());
                }
                case "GUI" -> readGui(in);
                default -> {
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            getLogger().warning("Ungültige Proxy-Nachricht: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- GUI

    private void readGui(DataInputStream in) throws IOException {
        UUID viewer = UUID.fromString(in.readUTF());
        String title = in.readUTF();
        int rows = Math.max(1, Math.min(6, in.readInt()));
        int count = in.readInt();
        Gui gui = new Gui();
        Inventory inv = Bukkit.createInventory(gui, rows * 9, LEGACY.deserialize(title));
        gui.inventory = inv;
        for (int i = 0; i < count; i++) {
            int slot = in.readInt();
            String material = in.readUTF();
            String name = in.readUTF();
            int loreSize = in.readInt();
            List<Component> lore = new ArrayList<>();
            for (int l = 0; l < loreSize; l++) lore.add(plain(in.readUTF()));
            String left = in.readUTF(), right = in.readUTF(), shift = in.readUTF(), skull = in.readUTF();
            if (slot < 0 || slot >= inv.getSize()) continue;
            inv.setItem(slot, item(material, name, lore, skull));
            gui.actions.put(slot, new String[]{left, right, shift});
        }
        // Rand füllen
        ItemStack glass = item("GRAY_STAINED_GLASS_PANE", " ", List.of(), "");
        for (int s = 0; s < inv.getSize(); s++) {
            boolean border = s < 9 || s >= inv.getSize() - 9;
            if (border && inv.getItem(s) == null) inv.setItem(s, glass);
        }
        Player p = Bukkit.getPlayer(viewer);
        if (p != null) p.openInventory(inv);
    }

    private static Component plain(String legacy) {
        return LEGACY.deserialize(legacy).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private ItemStack item(String material, String name, List<Component> lore, String skullOwner) {
        Material m = Material.matchMaterial(material);
        if (m == null || !m.isItem()) m = Material.PAPER;
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(plain(name));
        if (!lore.isEmpty()) meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        if (meta instanceof SkullMeta skull && !skullOwner.isEmpty()) {
            Player online = Bukkit.getPlayerExact(skullOwner);
            if (online != null) skull.setOwningPlayer(online);
            else skull.setPlayerProfile(Bukkit.createProfile(skullOwner));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui gui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        String[] actions = gui.actions.get(e.getRawSlot());
        if (actions == null) return;
        ClickType click = e.getClick();
        String cmd = click.isShiftClick() && !actions[2].isEmpty() ? actions[2]
                : click.isRightClick() && !actions[1].isEmpty() ? actions[1] : actions[0];
        if (cmd.isEmpty()) return;
        p.closeInventory();
        run(p, cmd);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- Tab

    private void updateTab(Player p) {
        if (!tabEnabled) return;
        String tag = clanTags.get(p.getUniqueId());
        if (tag == null) {
            p.playerListName(null);
            return;
        }
        String format = getConfig().getString("clantag-in-tab.format", "&f{name} &8[&e{tag}&8]");
        p.playerListName(AMP.deserialize(format.replace("{name}", p.getName()).replace("{tag}", tag)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                announce(p);
                updateTab(p);
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clanTags.remove(e.getPlayer().getUniqueId());
        onlineTime.remove(e.getPlayer().getUniqueId());
    }
}
