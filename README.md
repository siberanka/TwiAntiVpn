# TwiAntiVpn

TwiAntiVpn is a VPN/proxy and geo-blocking plugin for Spigot/Paper, BungeeCord and Velocity.

Version format: `YYYY.MM.DD.build`, for example `2026.07.09.2`.

## Features

- Blocks VPN/proxy users through configurable detection providers.
- Checks a local proxy/IP blocklist before cache lookups and before external VPN APIs.
- Downloads `.txt` and `.ipset` blocklists on a refresh schedule, one source at a time.
- Retries failed blocklist downloads up to 3 times and waits 1 second between sources/retries.
- Uses immutable blocklist snapshots to avoid desync while players are joining.
- Supports IP, `ip:port` and CIDR entries in downloaded blocklists.
- Blocks configured ISP names and ASN numbers after checking supported geo/IP providers in order.
- Blocks usernames containing configured terms before VPN/API checks.
- Skips VPN/geo/ISP checks for Asteroid fake players when Asteroid is installed and its registry confirms the UUID.
- Ships editable language files for English, Turkish, Azerbaijani and Spanish. English is the default.
- Supports geo blacklist/whitelist checks, cache providers and Discord webhooks.

## Default Proxy Blocklists

The default `config.yml` includes these blocklist sources:

- `https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt`
- `https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt`
- `https://raw.githubusercontent.com/scriptzteam/ProtonVPN-VPN-IPs/main/exit_ips.txt`
- `https://raw.githubusercontent.com/mmpx12/proxy-list/master/ips-list.txt`
- `https://check.torproject.org/torbulkexitlist?ip=1.1.1.1`
- `https://cinsscore.com/list/ci-badguys.txt`
- `https://lists.blocklist.de/lists/all.txt`
- `https://raw.githubusercontent.com/vakhov/fresh-proxy-list/refs/heads/master/socks4.txt`
- `https://blocklist.greensnow.co/greensnow.txt`
- `https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/stopforumspam_7d.ipset`
- `https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies.txt`
- `https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks4.txt`

## Build

```bash
./gradlew clean shadowJar
```

On this Windows workspace, Gradle may need the Windows certificate store:

```powershell
$env:GRADLE_OPTS='-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
F:\gradle-8.14.3\bin\gradle.bat clean shadowJar
```

The main release jar is generated at:

```text
build/libs/TwiAntiVpn-2026.07.09.2-all.jar
```

## Commands

- `/connectionguard help`
- `/connectionguard reload`
- `/connectionguard clear (<Player/UUID/IP>)`
- `/connectionguard info <Player/UUID/IP>`

## Credits

Original project by `gerolndnr`.

Maintained for TwiAntiVpn by `siberanka`.

## License

MIT
