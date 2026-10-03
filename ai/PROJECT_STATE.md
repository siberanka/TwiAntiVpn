# Project State

Last updated: 2026-10-03 (release `2026.10.03.1`).

## Overview

TwiAntiVpn is a single all-in-one jar for Spigot/Paper, BungeeCord/Waterfall and Velocity that
blocks VPN, proxy, Tor, geo-restricted, ISP/ASN-restricted and bot-named connections. It is a fork
of [gerolndnr/connection-guard](https://github.com/gerolndnr/connection-guard) (MIT); see
`UPSTREAM_ATTRIBUTION.md`.

- Canonical project: GitLab `siberanka/TwiAntiVpn`. GitHub `siberanka/TwiAntiVpn` is kept in sync
  (same history, same tags).
- Versioning: `YYYY.MM.DD.build`, set in the root `build.gradle.kts`.
- Java: `core`, `spigot`, `bungeecord` target Java 8; `velocity` targets Java 17.

## Modules

| Module | Role |
|---|---|
| `core` | All detection logic, caches, providers, Sonar hook, message formatting and language migration. Platform independent and unit tested. |
| `spigot` | `AsyncPlayerPreLoginEvent` listener, commands, Bukkit config/language loading. |
| `bungeecord` | `PreLoginEvent` listener, commands, Bungee config/language loading. |
| `velocity` | `PreLoginEvent` listener, commands, boosted-yaml config/language loading (`CGVelocityConfig`). |

## Login-check pipeline

1. Temporary runtime IP whitelist (`/twiantivpn whitelist <IP>`) bypasses everything.
2. Username contains-filter.
3. Asteroid fake-player bypass.
4. VPN verdict (`ConnectionGuard.getVpnResult`): proxy blocklist first, then aggregate cache,
   then the enabled VPN providers (`required-positive-flags`).
5. Trusted local ISP exemption (`ConnectionGuard.isVpnAsnWhitelisted` -> `VpnAsnWhitelistService`).
6. ISP/ASN block, then geo block.

Sonar integration (`SonarApiEarlyCheckHook`, `AdaptiveLoginOrderService`) can run selected modules
before Sonar verification and moves everything after Sonar during attacks.

## Trusted local ISP exemption

Config: `behavior.vpn.whitelisted-asn`.

- Trusted set = built-in list for `built-in-countries` (`TrustedResidentialIsps`) + `asns` - `excluded-asns`.
- A positive VPN verdict is ignored only when the geo ASN is trusted **and**:
  - `block-hosting: true` and the geo provider did not flag the address as hosting
    (`GeoResult.isHosting()`, from ip-api `hosting` or ProxyCheck `type`), and
  - `block-anonymizers: true` and the verdict carries no hard anonymizer evidence
    (`VpnResult.isAnonymizer()`).
- Hard anonymizer evidence: ProxyCheck `type: TOR` or a named `operator`; VPNAPI `tor`/`relay`;
  a custom provider returning a VPN provider name; any proxy blocklist source classified as
  `ANONYMIZER` (`ProxyBlocklistService.classifySource`: URL tokens for Tor, VPN, proxy, SOCKS,
  anonymous, cloud/datacenter/hosting). Abuse/spam reputation sources are `REPUTATION` and may be
  exempted for trusted ISPs.
- The anonymizer flag is persisted in the SQLite (`anonymizer` column) and Redis (Gson) VPN cache;
  the hosting flag is persisted in the geo cache (`hosting` column, NULL = unknown).
- Failed geo/ASN lookups keep the original VPN verdict (fail-closed).

The built-in list is maintained in code so that existing configs receive list updates with each
release. The old 34-entry list in `asns` of existing configs stays valid; it is merged with the
built-in list. See `TRUSTED_ISP_DATA.md` for the data process.

## Language files

`LanguageFileUpdater` (core) is used by all three platforms through small `Document` adapters.
It only adds keys missing from the user's file (defaults: bundled file of the same name, otherwise
`en.yml`) and renames legacy `/cg` and `/connectionguard` references in `command.unknown-subcommand`
and `messages.help`. A timestamped `.bak` copy is written before any change. Customized values are
never overwritten. Earlier releases replaced the whole file whenever a key was missing, which reset
customized messages after updates; that behavior is gone.

## Config compatibility rules

- Never rename or remove existing keys without a runtime fallback.
- New keys must be added on all platforms: Spigot uses `copyDefaults`; Bungee and Velocity use the
  `setConfigDefault` lists in `ConnectionGuardBungeePlugin.ensureAdaptiveLoginConfig` and
  `CGVelocityConfig.ensureAdaptiveLoginConfig`.
- Bungee's YAML writer drops comments when it saves; it only saves when a default was added.

## Testing

- `./gradlew clean test shadowJar` builds and runs all JUnit 5 tests in `core`.
- Blocklist and trusted-ISP integration tests use a local `com.sun.net.httpserver.HttpServer`; no
  external network access is needed for tests.
- Platform YAML adapters were verified against Bukkit `YamlConfiguration`, Bungee `Configuration`
  and boosted-yaml `YamlDocument` with a customized, outdated language file (custom values kept,
  four missing keys added, legacy help command migrated, second pass is a no-op).
- If Gradle fails with PKIX errors behind TLS inspection on Windows, run it with
  `-Djavax.net.ssl.trustStoreType=Windows-ROOT` (also in `GRADLE_OPTS`).

## Release workflow

- Bump the version in the root `build.gradle.kts` and in the Velocity `@Plugin` annotation and
  `VERSION` constant (`ConnectionGuardVelocityPlugin`); `plugin.yml`/`bungee.yml` are expanded by Gradle.
- Built and released locally under the `siberanka` identity. No CI/CD pipelines or GitHub Actions
  are used unless explicitly requested.
- Push `master` and the version tag to both remotes (`gitlab`, `origin`).
- GitHub release: upload `build/libs/TwiAntiVpn-<version>-all.jar`.
- GitLab release: upload the jar to the generic package `github-release-assets/<version>/` and link
  it with `filepath: /plugin.jar`, so the README badges
  (`/-/releases/permalink/latest/downloads/plugin.jar`) always resolve to the newest jar.
- Release notes: "What's changed" + "Verification" (build command, test count, artifact, SHA-256).

## Open items

- GitLab has no release entries for `2026.09.12.1` and `2026.09.17.1` (tags exist; GitHub has the
  releases). Backfill if a complete GitLab release history is needed.
- Bukkit/Bungee saves of `config.yml` and language files can drop YAML comments on old server
  versions; a comment-preserving writer would be an improvement.
