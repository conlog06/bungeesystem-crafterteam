package de.crafterteam.bungeesystem.social;

import de.crafterteam.bungeesystem.core.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Gemeinsame DB-Helfer für Freunde, Party & Clan (Tabellen des LobbySystems). */
public final class Social {

    private Social() {
    }

    /** Einstellungen aus ls_players.friend_settings ("req=1;party=1;notify=1") – Standard: an. */
    public static boolean setting(String settings, String key) {
        return settings == null || !settings.contains(key + "=0");
    }

    public static String settings(Connection con, UUID uuid) throws SQLException {
        String s = Database.string(con, "select friend_settings from ls_players where uuid = ?", uuid.toString());
        return s == null ? "" : s;
    }

    /** Setzt einen Schalter und gibt den neuen Einstellungs-Text zurück. */
    public static String toggle(Connection con, UUID uuid, String key, boolean[] newState) throws SQLException {
        String current = settings(con, uuid);
        boolean on = !setting(current, key);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("req", "1");
        map.put("party", "1");
        map.put("notify", "1");
        for (String part : current.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq), part.substring(eq + 1));
        }
        map.put(key, on ? "1" : "0");
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ";").append(k).append('=').append(v));
        Database.exec(con, "update ls_players set friend_settings = ? where uuid = ?", sb.toString(), uuid.toString());
        newState[0] = on;
        return sb.toString();
    }

    /** UUID → Name für mehrere Spieler. */
    public static Map<UUID, String> names(Connection con, Collection<UUID> ids) throws SQLException {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try (PreparedStatement ps = con.prepareStatement("select uuid, name, last_seen from ls_players where uuid in (" + in + ")")) {
            int i = 1;
            for (UUID id : ids) ps.setString(i++, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
            }
        }
        for (UUID id : ids) out.putIfAbsent(id, id.toString().substring(0, 8));
        return out;
    }

    public static long lastSeen(Connection con, UUID id) throws SQLException {
        return Database.longValue(con, "select last_seen from ls_players where uuid = ?", 0, id.toString());
    }
}
