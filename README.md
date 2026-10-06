# 🐱 Catgirl Launcher

A cute Minecraft launcher in the style of Prism: separate **instances** (each with its own version, mods and worlds), **Fabric** support, a **Modrinth mod and modpack browser**, automatic **Java downloads**, **12 themes plus a custom colour picker**, **fonts**, **backgrounds** (built-in art, your own picture or an image link), a movable **menu** (left/right/top/bottom, icons only), **auto-updates**, and its own **in-game title screen** ("CatGirl Launcher" title, "CatGirl Client" window name, catgirl splash texts and a quick menu).

Minecraft runs separately from the launcher, so closing the launcher never closes the game. The home screen shows the last instance you played and the real icon, MOTD and player count of the last server you joined. When you change an instance's Minecraft version, its Modrinth mods are swapped for the matching versions automatically.

## Run it on your PC

1. Install **Node.js LTS** from https://nodejs.org
2. Open a terminal in this folder and run:
   ```
   npm install
   npm start
   ```
   If npm blocks install scripts, run `npm install-scripts approve electron`, `npm install-scripts approve electron-winstaller`, then `npm rebuild`.
3. Run the tests any time with `npm test`.

## Microsoft login

Your Azure client ID is already in `config.json`. Login works once Mojang approves it (form: https://aka.ms/mce-reviewappid). Until then, signing in stops with *"Mojang has not approved this launcher's client ID yet"*.

## Put it on GitHub (needed for auto-updates and the in-game menu)

GitHub builds everything for you, including the in-game mod, which needs Minecraft's build tools.

1. Make a free account at https://github.com and install **GitHub Desktop** (https://desktop.github.com).
2. In GitHub Desktop: **File → Add local repository →** pick this folder → it offers to **create a repository** → do that, name it `catgirl-launcher`.
3. Click **Publish repository**. It can be public or private; public is simplest for downloads and updates.
4. On github.com, open your repo → **Actions** tab → enable workflows if asked.
5. Make your first release: **Actions → Release → Run workflow**, type `0.2.0`, and click **Run workflow**.

After about 10 minutes the **Releases** page has:
- `Catgirl-Launcher-Setup-0.2.0.exe`: the Windows installer to give to players
- `Catgirl-Launcher-0.2.0.AppImage`: Linux version
- `catgirl-client-mc1.21.11.jar`: the in-game menu mod (the launcher installs it automatically)

The workflow fills in your GitHub username everywhere, so you don't need to edit anything.

### Releasing an update
Change what you want, commit and push in GitHub Desktop, then **Actions → Release → Run workflow** with a higher version (e.g. `0.2.1`). Everyone who installed the launcher gets a "Restart to update" banner.

To test a build without releasing, run the workflow with the version left empty.

## In-game menu (Catgirl Client mod)

The `mod/` folder is a small Fabric mod that adds to the Minecraft title screen:
- **CatGirl Launcher** in bold pink in the top-right corner, with "playing as <name>" under it
- a side menu: Screenshots, Mods Folder, Resource Packs, Change Skin, Settings

The launcher downloads it into **Fabric instances** on launch (along with Fabric API, which it needs). Players can turn it off in **Settings → Catgirl in-game menu**. It's currently built for **Minecraft 1.21.11**. To add a version, add it to `matrix.mc` in `.github/workflows/release.yml`; the mod only uses Fabric API screen events, so it usually ports with little or no change.

## Themes

Sakura (default), Lavender, Midnight, Strawberry, Mint and Cotton Candy (light), picked in **Settings**. Colours live at the top of `src/renderer/styles.css`; copy a `[data-theme="…"]` block to make your own and add it to `THEMES` in `src/renderer/app.js`.

## Optional: a featured server

Set `featured` in `config.json` to have the launcher create a ready-made instance for a server:
```json
"featured": { "name": "My Server", "address": "play.example.net", "mcVersion": "1.21.11", "loader": "fabric", "mods": ["fabric-api", "sodium"] }
```

## Where things are stored

`%APPDATA%\CatgirlLauncher` on Windows, `~/Library/Application Support/CatgirlLauncher` on macOS, `~/.config/CatgirlLauncher` on Linux.

```
versions/   libraries/   assets/   ← shared between instances (downloaded once)
runtime/java-21/ …                 ← Java downloaded automatically
instances/<id>/minecraft/          ← each instance's mods, worlds, options
accounts.json  settings.json
```

## How the code is laid out

```
src/main/main.js       window, IPC handlers, auto-updates
src/main/auth.js       Microsoft → Xbox → Minecraft login, token refresh
src/main/versions.js   Mojang version list, Fabric loader profiles
src/main/launch.js     downloads libraries/assets/natives, builds args, starts Java
src/main/rules.js      pure helpers (library rules, Maven paths, arguments), unit tested
src/main/java.js       downloads Eclipse Temurin for the version's Java requirement
src/main/instances.js  create / edit / delete instances
src/main/mods.js       Modrinth search + install (with dependencies), enable/disable
src/main/catmod.js     installs the in-game menu mod from GitHub Releases
src/renderer/          the launcher UI
mod/                   the in-game Fabric mod (built by GitHub Actions)
.github/workflows/     builds and publishes releases
```

## Discord status ("Playing Catgirl Launcher")

1. Go to https://discord.com/developers/applications → **New Application** → name it **Catgirl Launcher** (this name is what Discord shows).
2. Copy the **Application ID** into `config.json` as `discordClientId`.
3. In the app, open **Rich Presence → Art Assets** and upload the logo (a 1024×1024 PNG) with the name **logo**.

Players can turn it off, or hide the server name, in **Settings → Discord**. In Fabric instances the in-game mod shows the status itself (In the menus / Playing singleplayer / Playing on <server>), so it stays even if the launcher is closed. For vanilla instances the launcher shows it while it's open. Discord desktop needs to be running.
