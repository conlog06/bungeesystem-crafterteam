package de.crafterteam.bungeesystem.external;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Minimaler TeamSpeak-3-ServerQuery-Client (Raw-Protokoll über TCP, Standard-Port 10011).
 * Antworten werden synchron gelesen, "notify…"-Ereignisse gehen an den Event-Handler.
 */
public final class Ts3Query {

    /** Ergebnis eines Befehls: Datensätze (key=value) + Fehlercode. */
    public record Result(List<Map<String, String>> rows, int error, String message) {
        public boolean ok() {
            return error == 0;
        }

        public Map<String, String> first() {
            return rows.isEmpty() ? Map.of() : rows.get(0);
        }
    }

    private final Logger log;
    private Socket socket;
    private Writer out;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private volatile Consumer<Map<String, String>> eventHandler = e -> { };
    private volatile boolean connected;

    public Ts3Query(Logger log) {
        this.log = log;
    }

    public boolean connected() {
        return connected && socket != null && !socket.isClosed();
    }

    public void onEvent(Consumer<Map<String, String>> handler) {
        this.eventHandler = handler;
    }

    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(0);
        out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        // Begrüßung: "TS3" + Willkommenstext
        String hello = in.readLine();
        if (hello == null || !hello.startsWith("TS3")) throw new IOException("Kein TeamSpeak-ServerQuery auf " + host + ":" + port);
        in.readLine();
        connected = true;
        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("notify")) {
                        String l = line;
                        int sp = l.indexOf(' ');
                        Map<String, String> ev = parseRow(sp < 0 ? "" : l.substring(sp + 1));
                        ev.put("_event", sp < 0 ? l : l.substring(0, sp));
                        try {
                            eventHandler.accept(ev);
                        } catch (RuntimeException ex) {
                            log.warning("TS3-Ereignis: " + ex);
                        }
                    } else {
                        lines.add(line);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                connected = false;
            }
        }, "BungeeSystem-TS3");
        reader.setDaemon(true);
        reader.start();
    }

    /** Befehl senden und auf "error id=…" warten. */
    public synchronized Result exec(String command) {
        if (!connected()) return new Result(List.of(), -1, "nicht verbunden");
        try {
            lines.clear();
            out.write(command + "\n");
            out.flush();
            List<Map<String, String>> rows = new ArrayList<>();
            while (true) {
                String line = lines.poll(8, TimeUnit.SECONDS);
                if (line == null) return new Result(rows, -1, "Zeitüberschreitung");
                if (line.startsWith("error ")) {
                    Map<String, String> e = parseRow(line.substring(6));
                    int id = Integer.parseInt(e.getOrDefault("id", "-1"));
                    return new Result(rows, id, e.getOrDefault("msg", ""));
                }
                for (String row : line.split("\\|")) rows.add(parseRow(row));
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            connected = false;
            return new Result(List.of(), -1, e.getMessage());
        }
    }

    public void close() {
        connected = false;
        try {
            if (out != null) {
                out.write("quit\n");
                out.flush();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- Escaping

    static Map<String, String> parseRow(String row) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : row.trim().split(" ")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) m.put(part, "");
            else m.put(part.substring(0, eq), unescape(part.substring(eq + 1)));
        }
        return m;
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '/' -> sb.append("\\/");
                case ' ' -> sb.append("\\s");
                case '|' -> sb.append("\\p");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            sb.append(switch (n) {
                case 's' -> ' ';
                case 'p' -> '|';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '/' -> '/';
                default -> n;
            });
        }
        return sb.toString();
    }
}
