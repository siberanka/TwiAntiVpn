# TwiAntiVpn

TwiAntiVpn is a production-focused anti-VPN, proxy, geo and connection-risk plugin for Minecraft networks. It supports Spigot/Paper, BungeeCord/Waterfall and Velocity from a single release jar.

## Features

- VPN and proxy detection through configurable provider checks.
- Local proxy blocklist checks before cache lookups and external API calls.
- Remote IP list refresh support for common formats such as `.txt`, `.ipset`, `.lst`, `.netset` and extensionless lists.
- Plain IP, `ip:port`, comma-separated token and CIDR parsing.
- Built-in safety caps for blocklist size, line length, source size, refresh interval and request timeout.
- Duplicate protection for URLs, exact IPs, IPv4 networks and IPv6 CIDR networks.
- Memory-optimized primitive storage for large IPv4 blocklists.
- ISP and ASN blocking by provider name or autonomous system number.
- Username contains-filter for blocking obvious bot naming patterns before VPN/API work.
- Configurable per-module Sonar ordering with adaptive attack routing, local pre-Sonar block thresholds and recovery hysteresis.
- AsteroidProxy/AsteroidSpoofer support for verified fake-player bypasses.
- Geo blacklist/whitelist support.
- SQLite, Redis or disabled cache modes.
- Discord webhook and console command actions.
- Editable language files for English, Turkish, Azerbaijani and Spanish.
- Login block, command and staff notification messages support new lines, legacy color codes, hex colors and common MiniMessage-style tags.
- Message prefixes are configured once through `messages.prefix` and reused with `{prefix}` or `%PREFIX%`.

## Download

Download the latest jar from the GitHub Releases page:

https://github.com/siberanka/TwiAntiVpn/releases

Use the `TwiAntiVpn-*-all.jar` artifact.

## Installation

1. Download the latest `TwiAntiVpn-*-all.jar` from Releases.
2. Place it in the plugin folder of your Spigot/Paper, BungeeCord/Waterfall or Velocity server.
3. Start the server once to generate the configuration files.
4. Edit `config.yml` and the files under `translation/` as needed.
5. Restart the server or run `/twiantivpn reload`.

## Configuration

The default configuration is designed to work out of the box. Important sections include:

- `proxy-blocklist`: remote IP list URLs, refresh interval and safety limits.
- `provider.vpn`: external VPN/proxy detection providers.
- `provider.geo`: geo and ISP data providers.
- `provider.isp-block`: blocked ASN numbers and ISP/provider names.
- `username-filter`: blocked username fragments.
- `login-check`: fixed before/after ordering and adaptive per-module Sonar routing. By default the lightweight username filter and in-memory proxy blocklist are allowed before Sonar; remaining checks run after verification. During a Sonar attack, local pre-Sonar block spike or recovery window, every module moves after Sonar.
- `login-check.adaptive-sonar.pre-sonar-block-spike`: treats repeated pre-Sonar blocks as an attack signal. `count-blocks-within-seconds` defines how far back blocks are counted, and `trigger-after-blocked-connections` defines how many blocks are needed before all checks move after Sonar. The default is 15 blocks within 60 seconds.
- `security.action-cooldown-seconds`: suppresses repeated staff, webhook and command side effects without allowing blocked connections.
- `behavior`: kick, notify, command and webhook actions.

Most scalar values can be written without quotes. Empty strings and JSON examples remain quoted in the default config because YAML would otherwise treat them differently.

## Commands

- `/twiantivpn help`
- `/twiantivpn reload`
- `/twiantivpn clear (<Player/UUID/IP>)`
- `/twiantivpn info <Player/UUID/IP>`

Aliases:

- `/twiavpn`
- `/tavpn`
- `/antivpn`

## Permissions

- `twiantivpn.command`
- `twiantivpn.command.help`
- `twiantivpn.command.reload`
- `twiantivpn.command.clear`
- `twiantivpn.command.info`
- `twiantivpn.notify.vpn`
- `twiantivpn.notify.geo`
- `twiantivpn.notify.username`
- `twiantivpn.notify.isp`
- `twiantivpn.exemption.vpn`
- `twiantivpn.exemption.geo`

## Building

Requirements:

- Java 21 for building
- Gradle wrapper from this repository

Build the release jar:

```bash
./gradlew clean shadowJar
```

The all-in-one jar is written to `build/libs/`.

## Versioning

TwiAntiVpn uses date-based versions:

```text
YYYY.MM.DD.build
```

Example:

```text
2026.07.09.20
```

## Credits

Original project by `gerolndnr`.

Maintained by `siberanka`.

## License

MIT
