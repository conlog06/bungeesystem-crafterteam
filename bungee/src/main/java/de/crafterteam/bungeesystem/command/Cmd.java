package de.crafterteam.bungeesystem.command;

import de.crafterteam.bungeesystem.BungeeSystem;
import de.crafterteam.bungeesystem.core.Messages;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Basis für alle Befehle – Name & Aliase kommen aus config.yml → commands.&lt;key&gt;. */
public abstract class Cmd extends Command implements TabExecutor {

    protected final BungeeSystem plugin;
    private final String perm;

    protected Cmd(BungeeSystem plugin, String[] names, String permission) {
        super(names[0], null, Arrays.copyOfRange(names, 1, names.length));
        this.plugin = plugin;
        this.perm = permission;
    }

    protected Messages m() {
        return plugin.messages();
    }

    @Override
    public final void execute(CommandSender sender, String[] args) {
        if (perm != null && !sender.hasPermission(perm)) {
            m().send(sender, "general.no-permission");
            return;
        }
        try {
            run(sender, args);
        } catch (RuntimeException e) {
            m().send(sender, "general.error");
            plugin.getLogger().warning("Fehler bei /" + getName() + ": " + e);
        }
    }

    protected abstract void run(CommandSender sender, String[] args);

    protected List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? players(args[0]) : List.of();
    }

    @Override
    public final Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (perm != null && !sender.hasPermission(perm)) return List.of();
        return complete(sender, args);
    }

    // ---------------------------------------------------------------- Helfer

    protected ProxiedPlayer player(CommandSender sender) {
        if (sender instanceof ProxiedPlayer p) return p;
        m().send(sender, "general.only-players");
        return null;
    }

    protected static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    protected static String name(CommandSender s) {
        return s instanceof ProxiedPlayer p ? p.getName() : "Konsole";
    }

    public static List<String> players(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (ProxiedPlayer pl : ProxyServer.getInstance().getPlayers()) {
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
        }
        return out;
    }

    public static List<String> filter(String prefix, Collection<String> options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    public static List<String> filter(String prefix, String... options) {
        return filter(prefix, Arrays.asList(options));
    }
}
