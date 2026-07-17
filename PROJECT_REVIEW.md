# Project Review

Audit date: 2026-07-16  
Audited release metadata: 1.8.0  
Primary implementation: `src/main/java/me/example/statusembed/StatusEmbed.java`

## Executive Summary

DiscordSRVStatusEmbed has grown from a small start/stop status plugin into a broad Paper-to-Discord operations plugin. The current implementation covers a useful private-server feature set: server embeds, Discord commands, player statistics, reporting, staff tools, verification, announcement/changelog relays, and scheduled operational tasks.

The main risk is architectural rather than feature absence. Nearly all behavior is implemented in one approximately 1,600-line class. This makes the plugin fast to extend initially, but it also couples Bukkit listeners, Discord listeners, configuration, persistence, scheduling, embed rendering, and error handling. The build is compilable against the local dependency set, but the repository has no automated tests and contains stale/generated artifacts.

Overall score: **7.0/10** after the latest maintenance pass. The user-facing feature breadth is strong for a private server, while maintainability, testability, dependency management, and configuration migration still need substantial work before the project should be treated as a mature general-purpose plugin.

## Strengths

- The plugin has a clear single entry point and a small dependency footprint.
- Server state is generally moved to the Bukkit main thread before reading Paper APIs from Discord callbacks.
- Discord network operations normally use asynchronous JDA actions.
- Configuration covers server addresses, colors, channels, staff, reports, dashboards, backups, changelog, verification, and automation.
- `/dsreport` uses inventory menus, which are a good Geyser-compatible interaction primitive.
- Channel IDs are validated with numeric snowflake checks in most Discord workflows.
- The dashboard checks message ownership before editing and creates a replacement when the saved message belongs to another account.
- The plugin includes diagnostics, audit logging, backups, staff notes, and startup command-registration logging.
- The README now documents the implemented behavior and explicitly identifies unsupported roadmap items.

## Weaknesses

- `StatusEmbed.java` contains the complete application and is difficult to reason about or test in isolation.
- There are no unit, integration, mock-JDA, or Paper test fixtures.
- The POM declares Java 25 and a Paper snapshot API, limiting deployment portability.
- `dependency-reduced-pom.xml` is generated metadata and must remain synchronized with `pom.xml` and `plugin.yml`.
- `DiscordSRV-Build-1.30.5.jar` is a system-scoped dependency, so reproducible builds depend on a checked-in binary and Maven's systemPath behavior.
- Existing server configuration is not migrated when new keys are added; operators must merge settings manually.
- Configuration reads and writes occur from several asynchronous callbacks, creating thread-safety risk around Bukkit `FileConfiguration`.
- Some slash commands are richer only as legacy `!` commands because player arguments are not modeled as slash options.
- The source contains user-visible mojibake in several default strings in `config.yml` and embed literals, indicating an encoding cleanup is needed.
- Generated build outputs and multiple historical JARs are present under `target/`, and no `.gitignore` was found.
- The plugin's actual Discord thread, SQLite/MySQL, PlaceholderAPI, webhook, poll, giveaway, ticket, and economy integrations are absent.

## Architecture Review

### Package structure

The package contains one class: `me.example.statusembed.StatusEmbed`. Resources contain `plugin.yml`, `config.yml`, and the QR image. There are no service, model, repository, command, listener, or utility packages.

### Separation of concerns

Separation is weak. The class owns:

- plugin lifecycle and DiscordSRV subscription;
- Bukkit command dispatch and tab completion;
- Discord slash command declarations;
- JDA message and button listeners;
- report inventory/chat listeners;
- embed construction;
- file persistence for notes, audit logs, and backups;
- scheduled dashboard, purge, and backup tasks.

Extracting `EmbedFactory`, `DiscordGateway`, `ConfigService`, `ReportService`, `PlayerStatsService`, `BackupService`, and `DiagnosticsService` would reduce coupling without changing behavior.

### Extensibility

Adding one feature currently requires editing the same class and often the same switch statement. A command registry and feature modules would make command toggles and future slash options safer.

## Code Quality

Naming is mostly understandable, and methods such as `buildStaffEmbed`, `fetchLeaderboardAsync`, and `sendDiagnostics` communicate intent. The main quality problem is class size and repeated fully qualified JDA types. There are also repeated color parsing, channel lookup, validation, and error-reporting patterns.

The source has little inline documentation for public behavior and no tests documenting expected output. Several methods mix configuration access, Bukkit scheduling, JDA requests, and rendering in one method. The existing catch blocks log failures but do not consistently expose actionable Discord-side permissions or channel diagnostics.

## Configuration Review

The YAML is broad and grouped by feature, which is good. Defaults include real server/channel values, which is convenient for the owner's server but makes the artifact less reusable as a public template. The config also contains automatically managed fields such as dashboard and verification message IDs next to operator-edited fields.

Validation is inconsistent: many IDs are checked only when used, and invalid optional features generally log a warning rather than appearing in a consolidated startup report. There is no schema version or migration layer. The stale generated config problem is particularly important because `saveDefaultConfig()` does not merge new defaults into an existing file.

## Error Handling

The plugin logs missing channels, invalid numeric IDs, missing images, Discord failures, and backup failures. Dashboard ownership recovery is a good example of defensive recovery.

Improvements are needed for:

- retry policy with bounded exponential backoff for transient Discord failures;
- distinguishing permission failures, not-found errors, rate limits, and network failures;
- centralized error messages with the affected config path and a concrete fix;
- avoiding asynchronous access to mutable Bukkit configuration objects;
- handling startup ordering consistently across all scheduled features.

## Performance Review

The player leaderboard iterates all offline players and, for `BLOCKS_MINED`, all block materials. This is acceptable for a small private server but should be cached or moved to a bounded data service for larger player histories. The player list adds avatar URLs per field and the dashboard periodically queries Discord, which is reasonable at a ten-minute interval.

The cooldown map is pruned periodically, which prevents unbounded growth. Report state maps are cleared on submission but should also be cleared on quit, timeout, and plugin disable. The notes file is rewritten for every note change; a repository service could batch or serialize writes.

## Security Review

Positive controls include permission-gated admin commands, configured Discord moderator IDs for buttons, sanitized text input, bounded suggestion/report text, safe backup filename validation, and no IP-history collection.

Risks include:

- staff and moderation identifiers are configuration-based and should be protected with filesystem permissions;
- Discord role assignment requires correct role hierarchy and must be tested against an untrusted member;
- the default configuration contains operational channel IDs and a donation number, so public distribution should use placeholders;
- audit logs may contain staff names, report details, and Discord IDs and need retention guidance;
- user-generated Discord content should be escaped/limited consistently before it becomes an embed field.

## Dependency Review

`pom.xml` uses Paper API `1.21.8-R0.1-SNAPSHOT` with `provided` scope and DiscordSRV 1.30.5 as a local system dependency. Maven Compiler Plugin 3.13.0 and Shade Plugin 3.6.0 are explicitly pinned.

The strongest dependency concern is the system-scoped JAR: it is not resolved from a repository and can silently diverge from the production DiscordSRV version. The reduced POM is not synchronized with the current POM and should either be regenerated by the build or removed from source control.

## DiscordSRV Integration Review

The plugin subscribes to `DiscordReadyEvent`, checks `DiscordSRV.isReady`, obtains JDA from `DiscordSRV.getPlugin().getJda()`, registers a JDA listener, and implements `SlashCommandProvider`. This is an appropriate integration shape for the bundled DiscordSRV API.

The integration is tightly coupled to DiscordSRV's relocated JDA classes and version-specific APIs. The project does not use reflection despite earlier documentation suggestions. The bundled JDA surface does not expose the thread API needed for real suggestion threads. A future adapter should isolate these APIs and permit upgrade testing.

## User Experience

Installation is understandable once DiscordSRV is already configured. The new startup registration messages and diagnostics command help identify stale JAR deployments. Configuration remains the largest operator burden because existing files need manual merging and several feature IDs must be entered correctly.

The admin menu, backups, diagnostics, and automatic dashboard improve day-to-day operations. A first-run setup wizard and a startup summary of every feature/channel would make the experience significantly better.

## Documentation Review

The rewritten README now covers current commands, permissions, configuration, compatibility, limitations, and build details. Before this audit, documentation did not accurately distinguish implemented features from roadmap requests. The repository still lacks screenshots, release notes as separate files, contribution automation, and a license.

## Final Score

**6.8/10**

Feature coverage and private-server usefulness are above average. The score is reduced by the monolithic architecture, missing tests, stale build metadata, system-scoped dependency, configuration migration gap, and several roadmap items that are not implemented. Splitting services and adding a test/build pipeline would provide the largest quality improvement.
