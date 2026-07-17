# Improvements Backlog

This backlog is based on the current 1.8.0 source, POM, resources, and generated artifacts. Economy/Vault features are intentionally excluded from this backlog.

## High Priority

- [ ] **Split `StatusEmbed.java` into services**  
  **Category:** Refactoring · **Reason:** One class owns lifecycle, commands, listeners, persistence, scheduling, embeds, and Discord I/O.  
  **Expected impact:** Lower regression risk and easier testing.  
  **Difficulty:** High.  
  **Suggested implementation:** Extract `DiscordGateway`, `EmbedFactory`, `ReportService`, `StatsService`, `ConfigService`, `BackupService`, and listener classes while preserving the current public command behavior.

- [ ] **Add Paper and JDA integration tests**  
  **Category:** Testing · **Reason:** The repository has no tests for command registration, embeds, reports, dashboards, role verification, or configuration parsing.  
  **Expected impact:** Catch API and startup regressions before deployment.  
  **Difficulty:** High.  
  **Suggested implementation:** Add JUnit tests for pure builders, MockBukkit tests for command/listener behavior, and a mocked Discord gateway for queue/failure paths.

- [ ] **Add configuration schema versioning and migration**  
  **Category:** Configuration · **Reason:** `saveDefaultConfig()` does not merge newly introduced keys into an existing operator config.  
  **Expected impact:** Safe upgrades without manual YAML merging.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Add `config-version`, migrate known versions, preserve custom values, and report migrations in the console.

  **Status:** Initial versioning and missing-default merging are now implemented; explicit historical migrations remain.

- [ ] **Replace system-scoped DiscordSRV dependency strategy**  
  **Category:** Build · **Reason:** `pom.xml` requires a local `DiscordSRV-Build-1.30.5.jar`, while DiscordSRV is not resolved reproducibly.  
  **Expected impact:** Reproducible CI and clearer runtime compatibility.  
  **Difficulty:** High.  
  **Suggested implementation:** Publish/use a controlled Maven repository or a documented dependency acquisition step, pin checksums, and test against supported DiscordSRV versions.

- [ ] **Synchronize or remove `dependency-reduced-pom.xml`**  
  **Category:** Build cleanup · **Reason:** Generated metadata can drift from the main project version.  
  **Expected impact:** Prevent misleading release metadata.  
  **Difficulty:** Low.  
  **Suggested implementation:** Regenerate it during packaging or remove it from source control and ignore generated output.

- [ ] **Centralize Discord error classification and retries**  
  **Category:** Reliability · **Reason:** Most `queue()` failures only log a message and do not distinguish permissions, rate limits, missing channels, or transient network errors.  
  **Expected impact:** Faster recovery and actionable troubleshooting.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Wrap RestActions in a gateway with bounded exponential backoff, retry only transient failures, and log config path/channel/action on permanent failures.

- [ ] **Make file/config persistence single-threaded**  
  **Category:** Reliability · **Reason:** Audit, changelog, dashboard, and scheduled tasks can read or write Bukkit configuration from different threads.  
  **Expected impact:** Avoid races and partial config writes.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Serialize state changes through the Bukkit scheduler and use atomic temporary-file replacement for YAML/log writes.

## Medium Priority

- [ ] **Add real Discord thread support for suggestions**  
  **Category:** Feature · **Reason:** The current bundled JDA surface has no thread API, so suggestions only create a message and discussion prompt.  
  **Expected impact:** Keep suggestion discussion attached to its original post.  
  **Difficulty:** High.  
  **Suggested implementation:** Upgrade to a DiscordSRV/JDA version exposing thread creation, isolate it behind an adapter, and persist the thread ID with the suggestion.

- [ ] **Add slash-command options, validation, and autocomplete**  
  **Category:** API · **Reason:** Rich player commands currently require legacy `!profile <player>`/`!stats <player>` text arguments, while `/profile` is only an instruction.  
  **Expected impact:** Better Discord UX and safer input handling.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Use `OptionData` for player/category arguments, autocomplete online/offline names, and share validation with text commands.

- [ ] **Persist report state and button history**  
  **Category:** Moderation · **Reason:** Report button changes are edits to a Discord message but no structured report record is stored.  
  **Expected impact:** Searchable history, reopening, audit trails, and restart resilience.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Store report ID, message/channel IDs, target, reporter, status, handler, timestamps, and transitions in SQLite or a versioned YAML repository.

- [ ] **Add report timeouts and cleanup**  
  **Category:** Memory/reliability · **Reason:** In-memory report target/reason/detail maps are cleared on submission but not on quit, timeout, or disable.  
  **Expected impact:** Prevent stale state and memory retention.  
  **Difficulty:** Low.  
  **Suggested implementation:** Clear per-player state on quit and schedule expiration for incomplete reports.

  **Status:** Quit cleanup and configurable 30-minute expiration are now implemented; persisted report history remains.

- [ ] **Add startup health summary**  
  **Category:** Diagnostics · **Reason:** Diagnostics is command-driven and channel checks are distributed across feature paths.  
  **Expected impact:** Operators see all misconfigured features immediately.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Validate every enabled channel, role, permission, QR file, and command at startup and emit a green/yellow/red summary.

- [ ] **Add scheduled status dashboard tests and safe ownership recovery tests**  
  **Category:** Testing · **Reason:** The dashboard has already encountered foreign-message ownership errors.  
  **Expected impact:** Prevent message-edit regressions and duplicate dashboards.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Mock retrieve success, foreign author, missing message, edit failure, and replacement-message persistence.

- [ ] **Add release automation**  
  **Category:** CI/CD · **Reason:** The repository contains many hand-created `target` JARs and no workflow.  
  **Expected impact:** Repeatable versioned artifacts and checks.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Add GitHub Actions for Java 25, `mvn verify`, tests, artifact upload, and a release workflow that validates plugin/POM versions.

- [ ] **Normalize source/resource encoding**  
  **Category:** Code cleanup · **Reason:** Several configured and embedded strings render as mojibake such as `â—†` and `âœ…`.  
  **Expected impact:** Correct user-facing Discord and console text.  
  **Difficulty:** Low.  
  **Suggested implementation:** Convert all source/YAML to UTF-8, verify Maven resource encoding, and add an encoding check in CI.

- [ ] **Add configurable retention for audit and incident logs**  
  **Category:** Security/operations · **Reason:** `audit.log` grows without a documented size or retention limit.  
  **Expected impact:** Predictable disk usage and better privacy hygiene.  
  **Difficulty:** Low.  
  **Suggested implementation:** Rotate by size/date, keep a configured number of files, and document sensitive fields.

  **Status:** Size-based audit rotation is now implemented; configurable rotated-file retention remains.

## Low Priority

- [ ] **Add a real PlaceholderAPI expansion**  
  **Category:** API · **Reason:** The README correctly states that PlaceholderAPI is not currently supported.  
  **Expected impact:** Use plugin data in scoreboards and other server integrations.  
  **Difficulty:** Medium.  
  **Suggested implementation:** Add PlaceholderAPI as `softdepend`, register `%discordstatus_*%` values only when present, and unregister cleanly.

- [ ] **Add webhook mode**  
  **Category:** Integration · **Reason:** All Discord output currently goes through DiscordSRV/JDA.  
  **Expected impact:** Allow status-only deployments without a full DiscordSRV bot connection.  
  **Difficulty:** High.  
  **Suggested implementation:** Define a `DiscordTransport` interface with DiscordSRV and webhook implementations; protect webhook URLs as secrets.

- [ ] **Add poll and giveaway modules**  
  **Category:** Feature · **Reason:** The roadmap mentions polls and giveaways, but no command, persistence, winner selection, or interaction model exists.  
  **Expected impact:** More community engagement.  
  **Difficulty:** High.  
  **Suggested implementation:** Build on the same persistent interaction repository as reports, with expiry, one-vote-per-user rules, permissions, and restart recovery.

- [ ] **Add scheduled status presence**  
  **Category:** UX · **Reason:** The plugin sends a rich status dashboard but does not update the bot's activity/presence.  
  **Expected impact:** Server state is visible in Discord's member list.  
  **Difficulty:** Low.  
  **Suggested implementation:** Update presence only on the existing dashboard interval and restore it on reconnect.

- [ ] **Add screenshots and rendered embed examples**  
  **Category:** Documentation · **Reason:** README has a placeholder screenshots section and no committed visual examples.  
  **Expected impact:** Faster evaluation and easier configuration.  
  **Difficulty:** Low.  
  **Suggested implementation:** Add sanitized screenshots under `docs/images/` and keep them version-neutral.

- [ ] **Add `.gitignore` and clean generated artifacts**  
  **Category:** Repository hygiene · **Reason:** `target/` contains many generated historical JARs and classes.  
  **Expected impact:** Smaller, clearer repository and fewer accidental releases.  
  **Difficulty:** Low.  
  **Suggested implementation:** Ignore `target/`, local IDE files, server data, audit logs, backups, and local dependency artifacts.

- [ ] **Add an explicit open-source license**  
  **Category:** Release · **Reason:** No license file or declaration exists, so reuse rights are unclear.  
  **Expected impact:** Legal clarity for contributors and users.  
  **Difficulty:** Low.  
  **Suggested implementation:** Choose a license with the project owner and add `LICENSE` plus the POM metadata.
