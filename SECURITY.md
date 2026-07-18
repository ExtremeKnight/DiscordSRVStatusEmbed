# Security Policy

## Supported versions

Security fixes are applied to the latest release on the `main` branch. Older releases are historical records and may not receive security updates.

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability.

Use GitHub's private vulnerability reporting feature on the [Security advisories page](https://github.com/ExtremeKnight/DiscordSRVStatusEmbed/security/advisories) when available. Include:

- A clear description of the vulnerability and its impact.
- The affected version, server platform, Java version, and DiscordSRV version.
- Reproduction steps or a minimal proof of concept.
- Relevant logs with tokens, IDs, credentials, and personal information removed.
- Any suggested mitigation or fix.

Reports are reviewed privately. Please allow reasonable time for validation, remediation, and coordinated disclosure.

## Secrets

Never commit Discord bot tokens, database credentials, webhook URLs, server console credentials, or private player data. If a secret is exposed, revoke or rotate it immediately and report the incident privately.
