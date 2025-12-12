# ModSeeker 🔎

ModSeeker is a Paper plugin that works with the [Hidder Fabric](https://github.com/RAFIN-G/Hidder) mod to verify player mods on Minecraft servers. It ensures players comply with server policies and prevents clients from bypassing rules.

---

## ✨ Features

- **Mod Verification**: Ensures players have the required Hidder mod installed  
- **Blacklist Enforcement**: Blocks players with blacklisted mods  
- **Whitelist Support**: Trusted players bypass verification  
- **Configurable Timeouts**: Adjust handshake & modlist request timeouts  
- **Detailed Logging**: Tracks all verification events   
- **Floodgate Support**: Optionally bypass mod checks for Bedrock players   
- **Encrypted Communication**: Secured via RSA/AES with signatures  

---

## 🛠 Requirements

- PaperMC 1.21.1 or higher  
- Java 21+  
- Hidder Fabric mod installed on clients  

---

## 📦 Installation

1. Download the latest `ModSeeker.jar`  
2. Place it in your `plugins/` folder  
3. Start the server to generate default configuration files  
4. Edit `config.yml`, `modblacklist.json`, or `whitelist.json` as needed  
5. Restart the server  

---

## ⚙ Configuration

ModSeeker generates these files:

- `config.yml` — Main configuration file  
- `modblacklist.json` — Blacklisted mods  
- `whitelist.json` — Whitelisted players  

### `config.yml`

```yaml
# ModSeeker Configuration File
# This file contains all configurable options for the ModSeeker plugin
# ==================================================================

# Timeout Configuration
handshakeTimeoutSeconds: 10
modlistTimeoutSeconds: 15

# Mod List Filter
modlistFilter:
  - "java"
  - "minecraft"
  - "fabricloader"

# Kick Messages
kickMessages:
  missingHidder: "Please Install Hidder Mod To Enter The Server"
  blacklistedMods: "Please Remove {mods} Illegal Mod{plural} To Join The Server"
  modlistTimeout: "Player Verification Failed"
  modlistRequestFailed: "Player verification failed - unable to send mod list request."
  modCountExceeded: "You have too many mods installed. Maximum allowed: {maxMods}"

# Player Notification
enablePlayerNotifications: true
welcomeMessage: "Welcome To The Server"

# Mod Count Threshold
enableModCountThreshold: false
maxModCount: 50

# Mod List Display Settings
showModList: true
oneModPerLine: false
highlightMods: true
highlightBlacklistedMods: true

# Floodgate / Bedrock Player Handling
allowFloodgate: true 
```
### `modblacklist.json`

```json
{
  "#": "ModSeeker Blacklist Configuration File",
  "#": "Add mod IDs to the blacklist array below to prevent players from joining with those mods",
  "blacklist": []
}
```
### `whitelist.json`
```json
[]
```
---

## 💻 Commands


All commands require `modseeker.use` permission.

### Seek Commands
* **`/modseeker seek mod <playertag>`** — Requests and displays the mod list from a specific online player
* **`/modseeker status`** — Displays plugin status and verification statistics

### Blacklist Management
* **`/modseeker modblacklist add <modID>`** — Adds a mod to the blacklist
* **`/modseeker modblacklist remove <modID>`** — Removes a mod from the blacklist
* **`/modseeker modblacklist show`** — Displays all blacklisted mods

### Whitelist Management
* **`/modseeker whitelist add <playername>`** — Adds a player to the whitelist
* **`/modseeker whitelist remove <playername>`** — Removes a player from the whitelist
* **`/modseeker whitelist show`** — Displays all whitelisted players

### Configuration Commands
* **`/modseeker reload`** — Reloads all configuration files without restarting the server

---

---

## 🔒 How It Works

This is the general flow of verification when a player joins the server:

1.  **Player joins the server**
2.  Checks if the player is whitelisted $\rightarrow$ skips verification if yes
3.  Initiates cryptographic handshake with Hidder
4.  Requests the full client mod list
5.  Verifies the payload signature and timestamp for authenticity
6.  Checks the mod list against the blacklist, enforces mod count limits, and verifies handshake timeouts
7.  Approves the player or kicks them with a custom message

---

## 🛡 Security Features

* **Native Encryption** via the Hidder mod
* **RSA/AES Hybrid Encryption** for the secure modlist payload
* **Digital Signatures** for message validation and integrity
* **Replay Attack Prevention** using timestamps
* Blacklist and whitelist support
* Floodgate / Bedrock optional bypass

---

## 🏗️ Building From Source

To compile ModSeeker from the source code:

1.  Clone the repository:
    ```bash
    git clone [Your Repository URL Here] 
    # (Replace with the actual URL)
    ```
2.  Navigate to the project directory.
3. **🔑 Key Generation (Required)**
Before building, you must generate secure RSA keys:
* Go to the `Tools` folder.
*Run [GenerateKeys.bat](cci:7://file:///d:/MINECRAFT/minecraft%20project/HERE/Tools/GenerateKeys.bat:0:0-0:0).
*Copy the contents of the generated `SERVER_KEY_JAVA.txt` and `SERVER_VERIFY_KEY.txt` into [src/main/java/com/example/modseeker/SecurityManager.java](cci:7://file:///d:/MINECRAFT/minecraft%20project/HERE/ModSeeker/src/main/java/com/example/modseeker/SecurityManager.java:0:0-0:0).
* Copy `CLIENT_KEYS_CPP.txt` and use it for the Hidder client build.
4.  Run the build command:
    ```bash
    # Linux/macOS
    ./gradlew build

    # Windows
    gradlew.bat build
    ```

The resulting `.jar` file will be located in the `build/libs/` directory.

---

## 📄 License

ModSeeker is licensed under the **AGPL-3.0** license. See the `LICENSE` file for full details.
