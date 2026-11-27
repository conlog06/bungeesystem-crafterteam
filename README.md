# BungeeSystem – CrafterTeam

Netzwerk-System für **BungeeCord** (1.21 – 26.x, Java 21): Ban/Mute/Report, Freunde/Party/Clan, Onlinezeit, Chat & CMDBlock.
Arbeitet mit derselben MySQL-Datenbank wie das [LobbySystem](https://github.com/conlog06/lobbysystem-claude) und
[MissileWars](https://github.com/conlog06/missile-wars).

**Download:** [Releases → latest](https://github.com/conlog06/bungeesystem-crafterteam/releases/tag/latest) (wird bei jedem Push auf `main` gebaut)

| Datei | Wohin |
|---|---|
| `BungeeSystem-x.jar` | `plugins/` des **BungeeCord-Proxys** |
| `BungeeSystemAddon-x.jar` | `plugins/` jedes **Paper-Servers**, auf dem die GUIs (Reports, Strafen) gehen sollen |

## Funktionen

### Ban / Mute / Report
- **Vorlagen** in der `config.yml` (`/ban <Spieler> <ID>`), eigene Gründe mit `bungeesystem.ban.custom`
  (`/ban <Spieler> <Dauer> <Grund>`, Dauer z. B. `30m`, `12h`, `7d`, `2w`, `1mo`, `perm`)
- **Eskalation:** gleiche Vorlage erneut → doppelte Dauer, ab dem 4. Ban permanent
- **IP-Sperre:** bei einem Ban wird die IP blockiert – kein Umgehen mit Zweitaccount (Team wird benachrichtigt)
- **Alle Nachrichten editierbar** in der `messages.yml` (inkl. Ban-Screen, Klick-Buttons, Hex-Farben)
- **Report-GUI** mit allen offenen Reports (Addon): Linksklick = annehmen & hinspringen, Rechtsklick = schließen,
  Shift-Klick = Straf-GUI. Ohne Addon: klickbare Liste im Chat
- **Straf-GUI** `/punish <Spieler>` mit allen Vorlagen, Entbannen, Entmuten, Kicken, Check & History
- `/check` zeigt Status, Onlinezeit, aktive Strafen, IP & Accounts mit gleicher IP; `/history` alle Bans, Mutes & Kicks
- **Übernimmt das alte BanSystem:** die Tabellen `bs_bans`, `bs_mutes`, `bs_kicks` werden weiterverwendet und nur um neue Spalten ergänzt

### Notify
- `/notify` – Benachrichtigungen für `ban`, `mute`, `kick`, `report`, `chat` einzeln an/aus (wird gespeichert)

### Freunde / Party / Clan
- **Freunde:** Anfragen mit Klick-Buttons, Liste mit Server & Buttons (Nachspringen, Nachricht, Party), Online-/Offline-Meldung,
  `/friend msg`, Anfragen & Meldungen abschaltbar, Limit (Premium mehr)
- **Privatnachrichten:** `/msg`, `/r` (optional nur an Freunde), Spy für das Team
- **Party:** einladen, Leader übergeben, kicken, **Mitglieder folgen dem Leader auf jeden Server**, `/party warp`, Party-Chat `/pc`
- **Clan:** gründen gegen Coins (LobbySystem-Guthaben), Tag, Rollen (Leader/Moderator/Mitglied), Clan-Chat `/cc`,
  **Clantag hinter dem Namen in der Tab-Liste** (Addon bzw. LobbySystem/MissileWars) und im Chat-Format
- Gleiche Tabellen wie im LobbySystem → das Social-Menü in der Lobby zeigt alles weiter an

### Onlinezeit
- `/onlinetime [Spieler]`, `/onlinetime top` – netzwerkweit vom Proxy gezählt
- Im **LobbySystem-Scoreboard**: Platzhalter `{onlinetime}` · PlaceholderAPI `%lobbysystem_onlinetime%` bzw. `%bungeesystem_onlinetime%` (Addon)

### Chat
- **Anti-Spam:** Cooldown + Sperre für Nachrichten, die einer der letzten zu X % ähneln (Levenshtein)
- **Filter:** IPs, Domains (mit Whitelist) und eigene Wörter (auch `h4ck3r`-Schreibweisen) – sperren oder zensieren
- **Caps-Sperre**, **Globalmute** (`/globalmute [Server]`), **Chat leeren** (`/chatclear [Server|global]`)
- **Persönlicher Chat-Schalter** `/chat` (Permission `bungeesystem.chat.toggle`)
- **@Erwähnungen** – der Name wird für den markierten Spieler hervorgehoben + Hinweis in der Actionbar
- **Chat-Format** vollständig anpassbar (`{clan}{prefix}{name}{suffix} » {message}`), Prefix aus **LuckPerms** oder eigenen Gruppen
- **CMDBlock:** einzelne Befehle (`/pl`, `/ver`, `bukkit:…`) sperren, Bypass mit Permission

> **Chat-Format & MissileWars:** Server in `chat.format.ignored-servers` (Standard `MissileWars*`) behalten ihr eigenes Format –
> so funktioniert der Teamchat weiter. Mute, Globalmute, Spam- & Wortfilter gelten trotzdem überall.
> Wer in der Lobby die Rang-Badges aus dem Ressourcenpaket im Chat behalten will, trägt dort `Lobby*` ein.

### Team-Tools
- **TeamChat** `/tc` · **AdminChat** `/ac` · **TeamOnline** `/teamonline` · **JoinTo** `/jointo` · **WhereIs** `/whereis`
- **Warn** `/warn add|remove|list` – ab `warn.max` Verwarnungen automatisch Ban (Vorlage oder Dauer einstellbar)
- **Exemption** `/ea <Spieler> <ban|mute> <50%|7d>` – Bans & Mutes verkürzen
- **StaffStats** `/staff <Name> [7d]` – Bans, Mutes, Kicks, Warns, erledigte Reports eines Teammitglieds
- **Support** `/support [Anliegen]` – Team nimmt an (`/support accept`), beide schreiben im privaten Support-Raum
- **BugReport** `/bugreport <Server> <Text>` – Team: `list`, `info|accept|deny <ID>`; angenommene Bugs bringen Coins
- `/history <Spieler> clear` löscht erledigte Einträge (aktive Strafen bleiben)

### Netzwerk
- **Wartung** `/wartung on|off|set 10d|add|remove|list` – eigene MOTD, Kick-Screen, Release-Countdown in der Serverliste,
  Zutritt nur über die Whitelist (keine Permission – trag dich selbst ein!)
- **MOTD** `/motd normal|wartung 1|2 <Text>` mit `[>]` » · `[o]` ● · `[*]` ✦ · `[|]` ┃ und Hex-Farben
- **Hub** `/hub`, `/lobby`, `/l` – leerste Lobby; in einer Lobby teleportiert das LobbySystem zum Spawn
- **Server-Befehle** (`/bauserver`, `/devserver` … frei in `server-commands`)
- **Broadcast** `/bc` · **AutoBroadcast** (Liste, Intervall, zufällig) · **Help** `/help` (Seiten) · **Links** `/dc`, `/ts`, `/web`, `/forum`
- **Ping** `/ping` · **Online** `/online` · **WhereAmI** `/whereami` · **DebugReset** `/debugreset` (Online-Status nach Absturz bereinigen)
- **Module** `/bsys modules list|enable|disable|reload` – Module bis zum Neustart an-/ausschalten

### Community
- **Umfragen** `/poll create <Sekunden> <Frage>`, Abstimmen per Klick, `/poll close|info`
- **JoinMe** `/joinme` – Nachricht mit dem Kopf des Spielers (8×8 Pixel) und Klick zum Nachjoinen
- **Advert** `/advert <Name>` – wer dich geworben hat bekommt Coins, du auch (IP- & Zeit-Schutz)
- **Coins** `/coins [add|remove|set|get]` – das gemeinsame LobbySystem/MissileWars-Guthaben
- **RankInfo** `/rankinfo [Rang]` – Name, Prefix, Suffix, Gewicht (LuckPerms)
- **Chat-Befehle** `/chat globalmute|clear [Spieler]|filter list|add|remove`

### Discord & TeamSpeak (in der config einschalten)
- **Discord** über Webhooks (kein Bot nötig): Status-Nachricht mit MOTD, Spielerzahl & Servern, die sich selbst aktualisiert,
  und Log-Embeds bei Ban, Mute, Kick (Typen wählbar)
- **TeamSpeak-Supportbot** über ServerQuery: `/tsbot open|close`, im TS per Privatnachricht `!open`, `!close`, `!notify`, `!ruhe`
- **TeamSpeak-Verify** `/tsverify` → Code im TS → `/tsverify <Code>` → Servergruppe + Beschreibung = Minecraft-Name;
  `/tsverify delete`, `/tsverify remove <Spieler>`

### Ressourcenpaket (netzwerkweit)
- Das LobbySystem hostet das Pack (Icons, Rang-Badges, Menü-Items), der Proxy verteilt es: In der Lobby schickt es das LobbySystem,
  auf MissileWars & allen anderen Servern das **Addon** – mit derselben Pack-ID, geladen wird es **nur einmal pro Sitzung**
- Eigene URL möglich: `resourcepack.url` + `sha1` in der `config.yml`; `required: true` = Pflicht (Ablehnen → Kick)
- Leere Server werden angepingt → im Lobby-Navigator „Online" auch ohne Spieler

## Installation

1. `BungeeSystem-x.jar` in den Proxy, `BungeeSystemAddon-x.jar` in alle Paper-Server.
2. Proxy starten → `plugins/BungeeSystem/config.yml`: **MySQL-Daten der Lobby-Datenbank** eintragen.
3. Proxy neu starten. LobbySystem (ab 2.5) und MissileWars (ab 2.10) erkennen das BungeeSystem automatisch.
4. Optional: Gründe, Befehlsnamen (`commands:`), Chat-Format in der `config.yml`, Texte in der `messages.yml` anpassen →
   `/bungeesystem reload`.

## Befehle

| Befehl | Permission | Beschreibung |
|---|---|---|
| `/ban <Spieler> [ID \| Dauer Grund]` | `bungeesystem.ban` | Bannen (ohne ID: GUI / Gründe-Liste) |
| `/mute <Spieler> [ID \| Dauer Grund]` | `bungeesystem.mute` | Muten |
| `/unban`, `/unmute <Spieler>` | `bungeesystem.unban`, `.unmute` | Strafe aufheben |
| `/kick <Spieler> [Grund]` | `bungeesystem.kick` | Kicken |
| `/punish <Spieler>` (`/bansystem`) | `bungeesystem.punish.gui` | Straf-GUI |
| `/check <Spieler>` | `bungeesystem.check` (`.ip` für IP & Alts) | Spieler-Infos |
| `/history <Spieler>` | `bungeesystem.history` | Straf-History |
| `/banreasons` | `bungeesystem.ban` | Vorlagen anzeigen |
| `/report <Spieler> <Grund>` | – | Spieler melden |
| `/reports`, `/report accept\|close <ID>` | `bungeesystem.report.manage` | Reports bearbeiten |
| `/notify [Typ\|all]` | `bungeesystem.notify` + `bungeesystem.notify.<typ>` | Benachrichtigungen |
| `/friend …` (`/f`) | – | Freunde |
| `/msg`, `/r` | – | Privatnachrichten |
| `/party …` (`/p`), `/pc` | – | Party |
| `/clan …`, `/cc` | – | Clan |
| `/onlinetime [Spieler\|top]` | `bungeesystem.onlinetime` (`.others`) | Onlinezeit |
| `/globalmute [Server]` | `bungeesystem.chat.globalmute` | Chat aus/an |
| `/chatclear [Server\|global]` | `bungeesystem.chat.clear` | Chat leeren |
| `/chat` | `bungeesystem.chat.toggle` | Chat für sich selbst aus/an |
| `/bungeesystem reload\|info\|modules …` | `bungeesystem.admin` | Neu laden, Module schalten |
| `/tc`, `/ac` | `bungeesystem.teamchat`, `.adminchat` | Team-/Admin-Chat |
| `/teamonline` | – (`bungeesystem.team` = wird gelistet) | Team online |
| `/jointo`, `/whereis` | `bungeesystem.jointo`, `.whereis` | Nachspringen / finden |
| `/warn …` | `bungeesystem.warn` | Verwarnungen |
| `/ea <Spieler> <ban\|mute> <x%\|Zeit>` | `bungeesystem.exemption` | Strafe verkürzen |
| `/staff <Name> [Zeitraum]` | `bungeesystem.staffstats` | Team-Statistik |
| `/support …` | Team: `bungeesystem.support` | Support-Raum |
| `/bugreport …` | Team: `bungeesystem.bugreport.manage` | Bug melden |
| `/wartung …` | `bungeesystem.maintenance` | Wartung |
| `/motd …` | `bungeesystem.motd` | MOTD ändern |
| `/hub`, `/lobby`, `/l` | – | Zur Lobby |
| `/bc <Text>` | `bungeesystem.broadcast` | Rundnachricht |
| `/help`, `/dc`, `/ts`, `/web`, `/forum` | – | Hilfe & Links |
| `/ping [Spieler]`, `/online`, `/whereami` | `.ping.others`, `.online.servers` für Details | Infos |
| `/debugreset` | `bungeesystem.debug` | Online-Zähler bereinigen |
| `/poll …` | `bungeesystem.poll.create`, `.close` | Umfragen |
| `/joinme` | `bungeesystem.joinme` | JoinMe senden |
| `/advert <Name>` | – | Werber angeben |
| `/coins …` | Verwalten: `bungeesystem.coins.admin` | Coins |
| `/rankinfo [Rang]` | `bungeesystem.rankinfo` | Rang-Infos |
| `/tsbot open\|close` | `bungeesystem.tsbot` | TS-Support |
| `/tsverify …` | `remove`: `bungeesystem.tsverify.manage` | TS-Verify |


Alle Namen & Aliase sind in `config.yml → commands` änderbar (leere Liste = Befehl aus).

**Weitere Permissions:** `bungeesystem.ban.custom` / `.mute.custom` (eigene Gründe), `bungeesystem.ban.permanent`,
`bungeesystem.ban.override` (bestehenden Ban überschreiben), `bungeesystem.punish.protected` (kann nicht bestraft werden) und
`.protected.bypass`, `bungeesystem.report.bypass` (kein Cooldown), `bungeesystem.report.protected`,
`bungeesystem.chat.bypass` (Spam/Caps), `bungeesystem.chat.filter.bypass`, `bungeesystem.chat.color`,
`bungeesystem.chat.globalmute.bypass`, `bungeesystem.chat.clear.bypass`, `bungeesystem.cmdblock.bypass`,
`bungeesystem.msg.spy`, `bungeesystem.msg.bypass`, `bungeesystem.friends.premium`, `bungeesystem.party.premium`,
`bungeesystem.clan.free`, `bungeesystem.friends.jump.bypass`, `bungeesystem.history.clear`, `bungeesystem.chat.filter.manage`,
`bungeesystem.joinme.bypass`, `bungeesystem.server.<name>` (Server-Befehle).

> **Nicht enthalten:** NickModule – dafür müsste das Addon auf jedem Server das Spielerprofil (Name & Skin) umschreiben.
> Das TS-Verify setzt Gruppe & Beschreibung, aber kein Kopf-Icon (dafür bräuchte es den TS-Dateitransfer).

## Technik

- Kanal **`crafterteam:bungee`** (Plugin-Messaging) zwischen Proxy und Servern:
  `HELLO`, `ONLINETIME`, `CLANTAG`, `FRIEND_REFRESH`, `FRIEND_SETTINGS`, `COINS_TAKE`/`COINS_ADD`/`COINS_GET`/`COINS_RESULT`/`COINS_VALUE`, `SPAWN`, `GUI`, `RUN`, `ADDON`.
  Nachrichten von Clients auf diesem Kanal werden verworfen; Server dürfen nur BungeeSystem-Befehle im Namen
  des Spielers ausführen, über dessen Verbindung die Nachricht kam (Permissions werden normal geprüft).
- Coins für Clans: Der Proxy fragt zuerst den Server des Spielers (LobbySystem/MissileWars halten das Guthaben im Cache),
  antwortet keiner, wird direkt in `ls_players` abgebucht.
- Mehrere Proxys möglich: aktive Bans/Mutes werden jede Minute aus der DB synchronisiert.

## Build

```
mvn package
```
