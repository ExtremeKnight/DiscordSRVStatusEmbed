# ExeNovaLink

**All-in-one Discord ↔ Minecraft integration for ExeSMP, by ExeNova.**

ExeNovaLink runs its **own Discord bot** — it no longer needs DiscordSRV — and
connects to other plugins automatically when they are installed. It started
life as *DiscordSRVStatusEmbed*; existing settings migrate on first start.

> **Version:** 2.0.0 · **Server:** Paper 1.21+ · **Java:** 21+

## Features

| Area | What it does |
| --- | --- |
| **Chat bridge** | Two-way Minecraft ↔ Discord chat. Minecraft chat posts through a webhook with each player's name and skin. Join, leave, first join, death, advancement and server start/stop messages. Word filter, markdown escaping, reply context, and nobody can ping `@everyone`. |
| **Account linking** | `/link` in game or in Discord (or DM the bot the code). Optional "must link to join". Linked-member role, nickname sync, `/whois` for staff. |
| **Role sync** | Minecraft groups → Discord roles (LuckPerms, or the `group.<name>` permission from any permission plugin). |
| **Console channel** | Live server console in Discord. Allowed staff can run commands; blocked-command list, IP masking, audit log. |
| **Status** | Start/stop embeds, a live dashboard (players, uptime, CPU, RAM, health), "Playing 12/50" bot presence, stats channel names. |
| **Server info** | `/ip`, `/bedrock`, `/version`, `/players`, `/status`, `/motd`, `/ping`, `/help` — as slash commands and `!` prefix commands. |
| **Player info** | `/profile`, `/playtime`, `/seen`, `/stats`, leaderboards (playtime, kills, deaths, mobs, mined). |
| **Community** | Suggestions with voting, threads and staff buttons. FAQ, staff roster, GCash donations, announcement relay, changelog publisher. |
| **Moderation** | `/dsreport` GUI (Geyser-friendly) → Discord report with staff buttons. Staff notes, watchlist, incident log, punishment log, `/purge`. |
| **Verification** | A Discord button that grants the Minecraft role. |
| **Automations** | Config-driven triggers → actions (console command, Discord message or embed, give role, items, XP, sound…). |
| **Operations** | `/exenova reload` (also reloads schedules), in-game settings GUI, backups with auto-pruning, diagnostics, audit log. |

## Integrations

Every integration is optional and switches on when that plugin is installed. Disable any of them under `integrations:` in `config.yml`.

| Plugin | Used for |
| --- | --- |
| **PlaceholderAPI** | Placeholders in bridge formats and webhook names (`%luckperms_prefix%`, `%vault_prefix%`, Towny, mcMMO… anything with an expansion). Also provides `%exenovalink_*%` placeholders. |
| **LuckPerms** | Group lookups for role sync, including offline players. |
| **EssentialsX** | Muted players aren't bridged to Discord; vanish and AFK are respected. |
| **SuperVanish / PremiumVanish / CMI / EssentialsX vanish** | Vanished players' chat, joins and leaves are never announced (standard `vanished` metadata). |
| **Geyser / Floodgate** | Detects Bedrock players and gives them a proper avatar. |
| **AdvancedBan** | Punishment log, read from its punishment event. |
| **LiteBans / EssentialsX / CMI / vanilla / any ban plugin** | Punishment log from the staff commands they run (`/ban`, `/tempban`, `/mute`, `/kick`, …). |
| **NuVotifier / Votifier** | Vote announcements in Discord. |
| **DiscordSRV** | Not required. If installed, its existing account links are used for players not yet linked in ExeNovaLink, and you're warned if both chat bridges are on. |

## Setup

1. **Create the bot.** Go to the [Discord Developer Portal](https://discord.com/developers/applications) → New Application → **Bot** → Reset Token. On the same page enable **Message Content Intent** and **Server Members Intent**.
2. **Invite it.** OAuth2 → URL Generator → scopes `bot` and `applications.commands`. Give it: View Channels, Send Messages, Embed Links, Attach Files, Read Message History, Add Reactions, Manage Messages (for purge), Manage Webhooks (for bridge avatars), Manage Roles (for verification and role sync), Manage Nicknames (optional), Manage Channels (for stats channels), Create Public Threads.
3. **Install.** Put `ExeNovaLink-2.0.0.jar` in `plugins/` and start the server once. On first start Paper downloads JDA from Maven Central automatically (see *Offline hosts* below).
4. **Configure.** In `plugins/ExeNovaLink/config.yml` set `discord.bot-token` (or the `EXENOVA_DISCORD_TOKEN` environment variable) and `discord.guild-id`. Then enable the features you want: `bridge`, `linking`, `role-sync`, `console`, `punishments`, `channel-stats`…
5. Run `/exenova reload`, then `/exenova status` to check the bot is online.

> To get IDs, turn on Discord's *Developer Mode* (User Settings → Advanced), then right-click a channel, role or user → **Copy ID**.

### Moving from DiscordSRVStatusEmbed

- On first start, `plugins/DiscordSRVStatusEmbed/` is copied to `plugins/ExeNovaLink/`. The old folder is left untouched; delete it, and the old jar, once you're happy.
- The **only new required setting is `discord.bot-token`** (plus `discord.guild-id` for linking and role sync).
- Old commands (`/discordstatusreload`, `/dsdiag`, …) and old permissions (`discordstatus.*`) still work.
- `%discordstatusembed_*%` placeholders still work alongside the new `%exenovalink_*%`.
- You can keep DiscordSRV installed or remove it. If you keep it, turn off one of the two chat bridges.

### Offline hosts

Paper downloads JDA through `plugin.yml`'s `libraries:` section, which needs outbound access to Maven Central on first start. If your host blocks that, you'll need a build that bundles JDA inside the jar (shaded); ask for one.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/exenova [status]` | — | Bot, linking and bridge status |
| `/exenova reload` | `exenova.reload` | Reload config, automations and schedules (and log in, if the token is new) |
| `/exenova diagnostics \| config \| backup \| restore <file> \| integrations` | `exenova.config` | Admin tools |
| `/link [code]`, `/unlink` | `exenova.link` (everyone) | Link or unlink your Discord account |
| `/discord` | — | Discord invite and link status |
| `/dsreport [player]` | — | Report a player (GUI) |
| `/dsnote <add\|list\|clear> <player> [note]` | `exenova.notes` | Private staff notes |
| `/dswatch <add\|remove\|list> [player]` | `exenova.watchlist` | Watchlist with join alerts |

Discord slash commands: `/help /ip /java /server /bedrock /bedrockip /version /players /playerlist /status /motd /ping /profile /playtime /seen /stats /leaderboard /suggest /faq /staff /report /gcash /purge /prefix /link /unlink /whois`.

## Permissions

`exenova.*` grants everything. The individual nodes are `exenova.reload`, `exenova.config`, `exenova.notes`, `exenova.watchlist`, `exenova.reports.notify`, `exenova.link` (default: everyone) and `exenova.bridge.bypass-filter`. The matching legacy `discordstatus.*` nodes are still honoured.

## Placeholders

`%exenovalink_online%`, `_max%`, `_tps%`, `_uptime%`, `_version%`, `_discord_status%`, `_linked%`, `_discord_id%`, `_linked_count%`

## Security notes

- The bot token is a password. Keep `config.yml` private, or use the `EXENOVA_DISCORD_TOKEN` environment variable.
- Nobody can run console commands from Discord unless their user or role ID is in `console.allowed-*`. Keep the console channel private.
- Link codes expire after 10 minutes and are rate limited, so they can't be brute-forced.
- No relayed text can mention `@everyone`, `@here` or roles.
- Automation variables never include raw player chat, so it can't be injected into console commands.

## Building

```bash
mvn clean package
```

This produces `target/ExeNovaLink-<version>.jar` and runs the unit tests. Java 21+ is needed to build. DiscordSRV's jar is kept in the repo only to compile the optional DiscordSRV hook; it is never bundled.

## License

Not yet specified.
