# ModSeeker 🔍

> **⚠️Note:** This plugin requires the [Hidder](https://github.com/RAFIN-G/Hidder) Fabric mod installed on all clients.

ModSeeker is a Paper plugin that detects player mods and enforces launcher restrictions on your server. Paired with the <a href="https://github.com/RAFIN-G/Hidder">Hidder</a> Fabric mod, it sees what mods players have installed, what launcher they're using, and blocks anything you don't allow — all verified through encrypted, cryptographically signed communication that can't be spoofed.

<table>
<tr>
<td align="center" width="50%">
<h3>🔍 Mod Detection</h3>
<p>See every mod, resource pack, and shader a player has installed — in real time</p>
</td>
<td align="center" width="50%">
<h3>🛡️ Launcher Enforcement</h3>
<p>Know exactly which launcher each player is using and block the ones you don't trust</p>
</td>
</tr>
</table>


[![](https://github.com/gabrielvicenteYT/modrinth-icons/raw/main/Branding/Badge/badge-dark.svg)](https://modrinth.com/plugin/modseeker)

---

## Features

- **Mod Detection** — Detects all client-side mods, resource packs, and shaders via the [Hidder](https://modrinth.com/mod/hidder) companion mod
- **Launcher Detection & Enforcement** — Identifies the player's launcher using native OS-level inspection and enforces a configurable whitelist
- **3-Layer Verification** — Independent security layers that must all pass before a player is admitted
- **Encrypted Communication** — All data is encrypted end-to-end and integrity-verified
- **Player Whitelist** — Trusted players bypass verification entirely
- **Discord Webhooks** — Real-time notifications for joins, mod lists, blacklist violations, and verification failures
- **Floodgate Support** — Optionally bypass verification for Bedrock players
- **Admin Dashboard** — `/modseeker status` for live monitoring of verification statistics

## Version Compatibility

| Minecraft Version | Paper | Java | Status |
|-------------------|-------|------|--------|
| 26.1 / 26.1.1 | Paper 26.1+ | Java 25+ | ✅ Latest |
| 1.21.4 – 1.21.11 | Paper 1.21+ | Java 21+ | ✅ Supported |

ModSeeker's Paper plugin is forward-compatible — a single JAR works across all supported versions within the same API generation.

---

## Requirements

- PaperMC **1.21.4** or higher (including **26.1**)
- Java **21+** (Java **25+** for Minecraft 26.1)
- [Hidder](https://modrinth.com/mod/hidder) Fabric mod installed on clients

---

## Installation

1. Download the latest `modseeker.jar` from [Modrinth](https://modrinth.com/plugin/modseeker) or [Releases](https://github.com/RAFIN-G/ModSeeker/releases)
2. Place it in your server's `plugins/` folder
3. Start the server — configuration files are generated automatically
4. Edit `config.yml`, `modblacklist.json`, or `whitelist.json` as needed
5. Run `/modseeker reload` to apply changes

---

## Security Architecture

ModSeeker uses three independent verification layers. Each layer operates autonomously — disabling one does not weaken the others.

```
Player Joins
    |
    v
[ Layer 1: Cryptographic Handshake ]  -- Is this the authentic Hidder mod?
    |
    v
[ Layer 2: Launcher Detection ]       -- Is the player using an approved launcher?
    |
    v
[ Layer 3: Mod Verification ]         -- Does the mod list contain banned mods?
    |
    v
Player Approved ✅
```

### Layer 1 — Identity Verification

The server issues a unique cryptographic challenge for every connection. The client must produce a valid signed response using a native security module. Challenges are one-time-use with strict time limits, preventing replay attacks and spoofing.

### Layer 2 — Launcher Detection

The companion mod detects which launcher started the game using native OS-level inspection. The server validates the result against a configurable whitelist. Supports 15 launchers including Prism Launcher, MultiMC, CurseForge, Modrinth App, ATLauncher, GDLauncher, Technic, FTB App, Lunar Client, Badlion Client, Feather Client, LabyMod, TLauncher, SKLauncher, and Pojav Launcher.

### Layer 3 — Mod Verification

The client collects its installed mods, resource packs, and shader packs directly from the Fabric runtime registry. The data is encrypted end-to-end using hybrid RSA/AES and includes a session-bound integrity signature. The server decrypts and verifies the signature before inspecting the mod list.

---

## Configuration

ModSeeker generates the following files on first run:

| File | Purpose |
|------|---------|
| `config.yml` | Main configuration — timeouts, kick messages, display settings, Discord webhook |
| `modblacklist.json` | List of banned mod IDs |
| `whitelist.json` | Players who skip verification entirely |
| `client-list.yml` | Per-launcher whitelist for Layer 2 — enable/disable individual launchers |

### config.yml

```yaml
# ---------------------------------------------------------------
# Timeout Configuration
# ---------------------------------------------------------------
handshakeTimeoutSeconds: 10
modlistTimeoutSeconds: 15

# ---------------------------------------------------------------
# Mod List Filter
# Base mods hidden from console output (still checked)
# ---------------------------------------------------------------
modlistFilter:
  - "java"
  - "minecraft"
  - "fabricloader"

# ---------------------------------------------------------------
# Kick Messages
# Supports {mods}, {plural}, {maxMods} placeholders
# ---------------------------------------------------------------
kickMessages:
  missingHidder: "Please Install Hidder Mod To Enter The Server"
  blacklistedMods: "Please Remove {mods} Illegal Mod{plural} To Join The Server"
  modlistTimeout: "Player Verification Failed"
  modlistRequestFailed: "Player verification failed - unable to send mod list request."
  modCountExceeded: "You have too many mods installed. Maximum allowed: {maxMods}"

# ---------------------------------------------------------------
# Player Notification
# ---------------------------------------------------------------
enablePlayerNotifications: true
welcomeMessage: "Welcome To The Server"

# ---------------------------------------------------------------
# Mod Count Threshold
# ---------------------------------------------------------------
enableModCountThreshold: false
maxModCount: 50

# ---------------------------------------------------------------
# Mod List Display Settings
# ---------------------------------------------------------------
showModList: true
oneModPerLine: false
highlightMods: true
highlightBlacklistedMods: true

# ---------------------------------------------------------------
# Floodgate / Bedrock Player Handling
# ---------------------------------------------------------------
allowFloodgate: true

# ==================================================================
# Discord Webhook Integration
# ==================================================================
discord:
  enabled: false
  webhookUrl: "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL_HERE"
  events:
    playerJoin: true          # Notify when a player joins
    modListReceived: true     # Notify with the player's mod list
    blacklistViolation: true  # Notify when a banned mod is detected
    verificationFailed: true  # Notify when verification fails

# ---------------------------------------------------------------
# Mod Verification (Layer 3)
# Set to false to disable mod checking while keeping Layer 1-2 active
# ---------------------------------------------------------------
ModVerification: true

# ==================================================================
# Launcher/Client Verification (Layer 2)
# ==================================================================
launcherVerification:
  enabled: false
  kickMessage: "Please use an approved launcher to join this server"
  allowUnknownClients: false
```

### modblacklist.json

```json
{
  "blacklist": ["exampleCheatMod", "anotherBannedMod"]
}
```

### whitelist.json

```json
["trustedPlayerName"]
```

### client-list.yml

Controls which launchers are allowed to connect when Layer 2 (launcher verification) is enabled. Each launcher can be individually toggled.

```yaml
# Launcher/Client Whitelist
# enabled: true = allowed, enabled: false = blocked

prism:
  enabled: false
  name: "Prism Launcher"
multimc:
  enabled: false
  name: "MultiMC"
atlauncher:
  enabled: false
  name: "ATLauncher"
curseforge:
  enabled: false
  name: "CurseForge"
modrinth:
  enabled: false
  name: "Modrinth App"
gdlauncher:
  enabled: false
  name: "GDLauncher"
technic:
  enabled: false
  name: "Technic Launcher"
ftb:
  enabled: false
  name: "FTB App"
lunar:
  enabled: false
  name: "Lunar Client"
badlion:
  enabled: false
  name: "Badlion Client"
feather:
  enabled: false
  name: "Feather Client"
labymod:
  enabled: false
  name: "LabyMod"
tlauncher:
  enabled: false
  name: "TLauncher"
sklauncher:
  enabled: false
  name: "SKLauncher"
pojav:
  enabled: false
  name: "Pojav Launcher"
```

To allow a launcher, set its `enabled` to `true`. Players using a blocked or unrecognized launcher are kicked with the message configured in `config.yml` under `launcherVerification.kickMessage`.

---

## Discord Webhook

ModSeeker can send real-time notifications to a Discord channel via webhooks.

**Setup:**
1. In your Discord server, go to **Channel Settings → Integrations → Webhooks**
2. Create a new webhook and copy the URL
3. Paste the URL into `config.yml` under `discord.webhookUrl`
4. Set `discord.enabled` to `true`
5. Toggle individual event types under `discord.events`

**Supported Events:**

| Event | Trigger |
|-------|---------|
| `playerJoin` | Player joins and begins verification |
| `modListReceived` | Player's mod list is successfully received and logged |
| `blacklistViolation` | A banned mod is detected — includes the mod name(s) |
| `verificationFailed` | Verification times out or fails for any reason |

All webhook requests run asynchronously on a dedicated thread to avoid impacting server performance.

---

## Commands

All commands require the `modseeker.use` permission.

| Command | Description |
|---------|-------------|
| `/modseeker seek mod <player>` | Request and display a player's mod list |
| `/modseeker status` | Show plugin status, config summary, and verification statistics |
| `/modseeker modblacklist add <modID>` | Add a mod to the blacklist |
| `/modseeker modblacklist remove <modID>` | Remove a mod from the blacklist |
| `/modseeker modblacklist show` | Display all blacklisted mods |
| `/modseeker whitelist add <player>` | Add a player to the whitelist |
| `/modseeker whitelist remove <player>` | Remove a player from the whitelist |
| `/modseeker whitelist show` | Display all whitelisted players |
| `/modseeker reload` | Reload all configuration files |

---

## How It Works

When a player joins the server:

1. **Whitelist check** — Whitelisted players are admitted immediately
2. **Floodgate check** — Bedrock players are optionally admitted
3. **Presence detection** — Server waits for the Hidder mod to announce itself (5s timeout)
4. **Layer 1** — Server sends a cryptographic challenge; client must respond with a valid signature
5. **Layer 2** — Server verifies the detected launcher against the launcher whitelist
6. **Player freeze** — Player is frozen in place while Layer 3 runs
7. **Layer 3** — Server requests the encrypted mod list; verifies integrity signature and session binding
8. **Blacklist check** — Mod list is compared against `modblacklist.json`
9. **Mod count check** — If enabled, verifies the mod count is within the threshold
10. **Approval** — Player is unfrozen and admitted with an optional welcome message

If any step fails, the player is kicked with the appropriate configurable message.

---

## Building from Source

### Prerequisites

- JDK 21+ (JDK 25+ for Minecraft 26.1)
- Gradle (wrapper included)

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/RAFIN-G/ModSeeker
   ```

2. **Key Generation (Required)** 🔑

   Both ModSeeker and [Hidder](https://github.com/RAFIN-G/Hidder) share cryptographic keys. Before building either project, you must generate a fresh set of keys using the tool included in the [`Tools/`](https://github.com/RAFIN-G/ModSeeker/tree/main/Tools) folder.

   ```bash
   cd Tools
   javac KeyGen.java
   java KeyGen
   ```

   This generates **5 files**. Each file contains the exact line to find and replace — just open it and follow the instructions inside.

   **3 files go into this repo (ModSeeker):**

   | Generated File | Destination File | Variable to Find |
   |----------------|------------------|------------------|
   | `SERVER_KEY_JAVA.txt` | [`src/.../SecurityManager.java`](https://github.com/RAFIN-G/ModSeeker/blob/main/src/main/java/com/example/modseeker/SecurityManager.java) | `SERVER_PRIVATE_KEY = "REPLACE_WITH..."` |
   | `SERVER_VERIFY_KEY.txt` | [`src/.../SecurityManager.java`](https://github.com/RAFIN-G/ModSeeker/blob/main/src/main/java/com/example/modseeker/SecurityManager.java) | `DEFAULT_PUBLIC_KEY = "REPLACE_WITH..."` |
   | `HMAC_SECRET_JAVA.txt` | [`src/.../HandshakeManager.java`](https://github.com/RAFIN-G/ModSeeker/blob/main/src/main/java/com/example/modseeker/HandshakeManager.java) **AND** [`src/.../SecurityManager.java`](https://github.com/RAFIN-G/ModSeeker/blob/main/src/main/java/com/example/modseeker/SecurityManager.java) | `HMAC_SECRET = "REPLACE_WITH..."` (in **both** files — must be identical) |

   **2 files go into the [Hidder](https://github.com/RAFIN-G/Hidder) repo:**

   | Generated File | Destination File | Section to Find |
   |----------------|------------------|-----------------|
   | `CLIENT_KEYS_CPP.txt` | [`Hidder/native/hidder_vault.cpp`](https://github.com/RAFIN-G/Hidder/blob/main/native/hidder_vault.cpp) | The `RSA KEY COMPONENTS` section — replace all `B64_MODULUS`, `B64_PRIV_EXP`, `SRV_B64_MODULUS`, etc. |
   | `HMAC_SECRET_CPP.txt` | [`Hidder/native/hidder_vault.cpp`](https://github.com/RAFIN-G/Hidder/blob/main/native/hidder_vault.cpp) | The `ENCODED_HMAC_KEY[]` array inside the `computeHmac` function |

   > **Important:** Every deployment should use its own unique keys. Never reuse keys from another server. The HMAC secret must be **identical** across `HandshakeManager.java`, `SecurityManager.java`, and `hidder_vault.cpp` — all three files use it for Layer 1 and Layer 3 verification.

3. Build:
   ```bash
   ./gradlew jar      # Linux/macOS
   gradlew.bat jar    # Windows
   ```

The compiled JAR will be in `build/libs/`.

---

## License

This project is licensed under **AGPL-3.0**. See the [LICENSE](https://github.com/RAFIN-G/ModSeeker/blob/main/LICENSE) file for details.

