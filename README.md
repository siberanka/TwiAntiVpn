<!-- DOWNLOAD_BADGES_START -->
<p align="center">
  <a href="https://gitlab.com/siberanka/TwiAntiVpn/-/releases/permalink/latest/downloads/plugin.jar"><img alt="Download Paper" src="https://img.shields.io/badge/Download-Paper-2c2f33?logo=gitlab&logoColor=white"></a>
  <a href="https://gitlab.com/siberanka/TwiAntiVpn/-/releases/permalink/latest/downloads/plugin.jar"><img alt="Download Bukkit" src="https://img.shields.io/badge/Download-Bukkit-f39c12?logo=gitlab&logoColor=white"></a>
  <a href="https://gitlab.com/siberanka/TwiAntiVpn/-/releases/permalink/latest/downloads/plugin.jar"><img alt="Download BungeeCord" src="https://img.shields.io/badge/Download-BungeeCord-6f42c1?logo=gitlab&logoColor=white"></a>
  <a href="https://gitlab.com/siberanka/TwiAntiVpn/-/releases/permalink/latest/downloads/plugin.jar"><img alt="Download Velocity" src="https://img.shields.io/badge/Download-Velocity-1f6feb?logo=gitlab&logoColor=white"></a>
</p>
<!-- DOWNLOAD_BADGES_END -->

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
- Configurable VPN-result exemption for curated residential ISP ASNs, with Turkish and other Turkic-country access networks included by default.
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

Download the latest jar from the GitLab Releases page:

https://gitlab.com/siberanka/TwiAntiVpn/-/releases

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
- `behavior.vpn.whitelisted-asn`: ignores only VPN/proxy detections for configured ASNs. Geo, ISP/ASN and username policies remain active. The default list contains major end-user access networks in Turkey, Azerbaijan, Kazakhstan, Uzbekistan, Kyrgyzstan and Turkmenistan.
- `username-filter`: blocked username fragments.
- `login-check`: fixed before/after ordering and adaptive per-module Sonar routing. By default the lightweight username filter and in-memory proxy blocklist are allowed before Sonar; remaining checks run after verification. During a Sonar attack, local pre-Sonar block spike or recovery window, every module moves after Sonar.
- `login-check.adaptive-sonar.pre-sonar-block-spike`: treats repeated pre-Sonar blocks as an attack signal. `count-blocks-within-seconds` defines how far back blocks are counted, and `trigger-after-blocked-connections` defines how many blocks are needed before all checks move after Sonar. The default is 15 blocks within 60 seconds.
- `security.action-cooldown-seconds`: suppresses repeated staff, webhook and command side effects without allowing blocked connections.
- `security.error-log`: writes detailed plugin exceptions to `error.log` while keeping console messages short. The default is enabled, with a 2048 KB active log limit and automatic rotation to `error.log.1`.
- `update-check.enabled`: checks GitHub Releases first and falls back to GitLab Releases only when GitHub cannot return valid release metadata. It only prints an update notice; it never downloads or executes files.
- `behavior`: kick, notify, command and webhook actions.

Most scalar values can be written without quotes. Empty strings and JSON examples remain quoted in the default config because YAML would otherwise treat them differently.

An ASN whitelist reduces false positives but is not a guarantee that every address in that network is safe. Residential networks can contain compromised devices, customer-operated proxies and carrier-grade NAT. Keep the list limited to trusted access operators and review it periodically. If ASN lookup fails, TwiAntiVpn keeps the original VPN/proxy result.

## Sonar Integration

TwiAntiVpn can cooperate with Sonar on Spigot/Paper, BungeeCord/Waterfall and Velocity. The integration is controlled from `login-check`.

- `login-check.order: BEFORE_ANTIBOT` makes TwiAntiVpn run as early as the platform allows. This is the default.
- `login-check.order: AFTER_ANTIBOT` lets anti-bot plugins process the connection first, then TwiAntiVpn checks the connection if it is still allowed.
- When Sonar is installed and `BEFORE_ANTIBOT` is active, TwiAntiVpn opens a pre-verification gate at Sonar's `UserVerifyJoinEvent`. Incoming packet reads are paused while the selected modules run, so Sonar cannot inspect client settings, brand, keep-alive, gravity or vehicle packets first.
- The gate resumes the connection only after every selected check allows it. A policy match disconnects through Sonar's packet pipeline; an exception or timeout is denied fail-closed with the localized `pre-sonar-check-failed` message.
- `login-check.adaptive-sonar.pre-sonar-check-timeout-seconds` limits gate duration to 1-7 seconds. The default is 6 seconds, below Sonar's default 8-second read timeout.
- `login-check.adaptive-sonar.enabled` allows TwiAntiVpn to move checks after Sonar automatically during attacks or recovery periods.
- `login-check.adaptive-sonar.before-sonar` controls which modules are allowed before Sonar during normal traffic. These modules are checked early, and all enabled policies are checked again after Sonar if the connection is still allowed. By default, only lightweight checks are enabled there.

Default adaptive behavior:

- Normal traffic: modules enabled under `before-sonar` run early; all enabled checks are still available after Sonar if the connection remains allowed.
- Sonar attack signal: all active TwiAntiVpn checks move after Sonar.
- Recovery window: checks stay after Sonar until `recovery-delay-seconds` has passed without another attack signal.
- Local pre-Sonar block spike: if early checks block too many connections in a short time, TwiAntiVpn treats that as an attack signal even if Sonar has not reported one yet.

The local spike detector is configured here:

```yaml
login-check:
  adaptive-sonar:
    pre-sonar-check-timeout-seconds: 6
    pre-sonar-block-spike:
      enabled: true
      count-blocks-within-seconds: 60
      trigger-after-blocked-connections: 15
```

In plain terms, the default means: if 15 connections are blocked by pre-Sonar checks within 60 seconds, every active check is moved after Sonar. This helps Sonar absorb bot pressure first while TwiAntiVpn continues enforcing VPN, proxy, geo, ISP and username rules after verification.

For compatibility, older config keys are still accepted, but new configs should use `pre-sonar-block-spike`, `count-blocks-within-seconds` and `trigger-after-blocked-connections`.

## Commands

- `/twiantivpn help`
- `/twiantivpn reload`
- `/twiantivpn clear (<Player/UUID/IP>)`
- `/twiantivpn info <Player/UUID/IP>`
- `/twiantivpn whitelist <IP>`
- `/twiantivpn test <IP> [username]`
- `/twiantivpn test attack [on|off]`

`test <IP> [username]` evaluates the configured VPN/proxy, username, geo and ISP/ASN policies without disconnecting anyone. `test attack` shows the current module routing; `on` simulates an attack signal and `off` moves adaptive routing into recovery.

`whitelist <IP>` keeps the IP in memory until the server restarts and bypasses all TwiAntiVpn login checks for it.

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
- `twiantivpn.command.test`
- `twiantivpn.command.whitelist`
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
2026.07.09.26
```

## Credits

Original project by `gerolndnr`.

Maintained by `siberanka`.

## License

MIT

---

# TwiAntiVpn Türkçe

TwiAntiVpn, Minecraft ağları için production odaklı anti-VPN, proxy, geo ve bağlantı riski kontrol eklentisidir. Tek release jar dosyasıyla Spigot/Paper, BungeeCord/Waterfall ve Velocity destekler.

## Özellikler

- Yapılandırılabilir sağlayıcılarla VPN ve proxy tespiti.
- Harici API çağrılarından önce çalışan yerel proxy blocklist kontrolü.
- `.txt`, `.ipset`, `.lst`, `.netset` ve uzantısız IP listesi linklerini yenileme desteği.
- Düz IP, `ip:port`, virgüllü token ve CIDR okuma desteği.
- Liste boyutu, satır uzunluğu, kaynak boyutu, yenileme aralığı ve timeout için güvenlik sınırları.
- URL, IP, IPv4 ağ ve IPv6 CIDR tekrarlarını engelleyen duplicate koruması.
- Büyük IPv4 blocklistleri için bellek dostu depolama.
- ASN numarası veya ISP/sağlayıcı adına göre engelleme.
- Türkiye ve diğer Türk ülkelerindeki seçili son kullanıcı internet ağlarını varsayılan olarak içeren, düzenlenebilir VPN ASN muafiyeti.
- Bot isim kalıpları için kullanıcı adı içerik filtresi.
- Sonar ile modül bazlı sıralama, adaptive saldırı modu, pre-Sonar blok eşiği ve recovery bekleme süresi.
- AsteroidProxy/AsteroidSpoofer doğrulanmış fake player bypass desteği.
- Geo blacklist/whitelist desteği.
- SQLite, Redis veya cache kapalı modları.
- Discord webhook ve console command aksiyonları.
- İngilizce, Türkçe, Azerice ve İspanyolca dil dosyaları.
- Kick, komut ve yetkili bildirim mesajlarında yeni satır, legacy renk kodları, hex renkler ve yaygın MiniMessage tarzı tag desteği.
- Mesaj prefixleri `messages.prefix` üzerinden tek yerden ayarlanır ve `{prefix}` veya `%PREFIX%` ile kullanılır.

## İndirme

Son jar dosyasını GitLab Releases sayfasından indirin:

https://gitlab.com/siberanka/TwiAntiVpn/-/releases

`TwiAntiVpn-*-all.jar` dosyasını kullanın.

## Kurulum

1. Releases sayfasından en güncel `TwiAntiVpn-*-all.jar` dosyasını indirin.
2. Spigot/Paper, BungeeCord/Waterfall veya Velocity sunucunuzun plugin klasörüne koyun.
3. Config dosyalarının oluşması için sunucuyu bir kez başlatın.
4. `config.yml` ve `translation/` altındaki dil dosyalarını ihtiyacınıza göre düzenleyin.
5. Sunucuyu yeniden başlatın veya `/twiantivpn reload` komutunu çalıştırın.

## Yapılandırma

Varsayılan config doğrudan çalışacak şekilde hazırlanmıştır. Önemli bölümler:

- `proxy-blocklist`: uzak IP listesi linkleri, yenileme aralığı ve güvenlik sınırları.
- `provider.vpn`: harici VPN/proxy tespit sağlayıcıları.
- `provider.geo`: geo ve ISP veri sağlayıcıları.
- `provider.isp-block`: engellenecek ASN numaraları ve ISP/sağlayıcı adları.
- `behavior.vpn.whitelisted-asn`: ayarlanan ASN'ler için yalnızca VPN/proxy tespitini yok sayar. Geo, ISP/ASN ve kullanıcı adı kuralları çalışmaya devam eder. Varsayılan liste Türkiye, Azerbaycan, Kazakistan, Özbekistan, Kırgızistan ve Türkmenistan'daki büyük son kullanıcı erişim ağlarını içerir.
- `username-filter`: kullanıcı adında geçerse engellenecek ifadeler.
- `login-check`: anti-bot pluginlerinden önce/sonra çalışma sırası ve Sonar için adaptive modül yönlendirmesi.
- `login-check.adaptive-sonar.pre-sonar-block-spike`: Sonar öncesi engelleme yoğunluğunu saldırı sinyali olarak değerlendirir. `count-blocks-within-seconds` kaç saniyelik süreye bakılacağını, `trigger-after-blocked-connections` ise bu sürede kaç engellemeden sonra tüm kontrollerin Sonar sonrasına taşınacağını belirler.
- `security.action-cooldown-seconds`: aynı IP için staff notify, webhook ve console command gibi yan etkileri sınırlar; engelleme davranışını gevşetmez.
- `security.error-log`: detaylı plugin exception kayıtlarını `error.log` dosyasına yazar, konsolda ise kısa mesaj bırakır. Varsayılan olarak açıktır; aktif log sınırı 2048 KB'dir ve dolunca `error.log.1` olarak döndürülür.
- `update-check.enabled`: önce GitHub Releases'i kontrol eder; GitHub geçerli release verisi döndüremezse GitLab Releases'e geçer. Yalnızca güncelleme bildirimi yazar, dosya indirmez veya çalıştırmaz.
- `behavior`: kick, notify, command ve webhook aksiyonları.

Çoğu basit değer tırnaksız yazılabilir. Boş stringler ve JSON örnekleri YAML tarafından farklı yorumlanmasın diye varsayılan configte tırnaklı bırakılmıştır.

ASN whitelist yanlış pozitifleri azaltır ancak ağdaki her adresin güvenli olduğunu garanti etmez. Ev interneti ağlarında ele geçirilmiş cihazlar, kullanıcıların çalıştırdığı proxyler ve CGNAT bulunabilir. Listeyi yalnızca güvendiğiniz erişim operatörleriyle sınırlı tutun ve düzenli olarak gözden geçirin. ASN sorgusu başarısız olursa TwiAntiVpn mevcut VPN/proxy sonucunu korur.

## Sonar Entegrasyonu

TwiAntiVpn, Spigot/Paper, BungeeCord/Waterfall ve Velocity üzerinde Sonar ile birlikte çalışabilir. Bu davranış `login-check` bölümünden yönetilir.

- `login-check.order: BEFORE_ANTIBOT` TwiAntiVpn kontrollerini platformun izin verdiği en erken aşamada çalıştırır. Varsayılan budur.
- `login-check.order: AFTER_ANTIBOT` anti-bot pluginlerinin önce çalışmasına izin verir; bağlantı hâlâ izinliyse TwiAntiVpn kontrolleri sonra yapılır.
- Sonar kuruluysa ve `BEFORE_ANTIBOT` aktifse TwiAntiVpn, Sonar'ın `UserVerifyJoinEvent` aşamasında bir ön doğrulama kapısı açar. Seçili modüller çalışırken bağlantıdan paket okuma durdurulur; böylece Sonar client settings, client brand, keep-alive, gravity veya vehicle paketlerini önce inceleyemez.
- Bağlantı yalnızca seçili kontrollerin tamamı izin verdikten sonra devam eder. Bir politika eşleşirse bağlantı Sonar'ın packet pipeline'ı üzerinden kapatılır; exception veya zaman aşımı olursa dil dosyasındaki `pre-sonar-check-failed` mesajıyla fail-closed engellenir.
- `login-check.adaptive-sonar.pre-sonar-check-timeout-seconds` kapının açık kalacağı süreyi 1-7 saniye arasında sınırlar. Varsayılan 6 saniye, Sonar'ın varsayılan 8 saniyelik read timeout değerinden düşüktür.
- `login-check.adaptive-sonar.enabled` açıkken saldırı veya recovery durumlarında kontroller otomatik olarak Sonar sonrasına taşınabilir.
- `login-check.adaptive-sonar.before-sonar` normal trafikte hangi modüllerin Sonar öncesinde çalışabileceğini belirler. Bu modüller erken kontrol edilir; bağlantı hâlâ izinliyse tüm aktif politikalar Sonar sonrasında tekrar kullanılabilir. Varsayılan olarak burada yalnızca hafif kontroller açık tutulur.

Varsayılan adaptive davranış:

- Normal trafik: `before-sonar` altında açık olan modüller erken çalışır; bağlantı izinli kalırsa tüm aktif kontroller Sonar sonrasında da uygulanabilir.
- Sonar saldırı sinyali: tüm aktif TwiAntiVpn kontrolleri Sonar sonrasına taşınır.
- Recovery süresi: `recovery-delay-seconds` bitene kadar kontroller Sonar sonrasında kalır.
- Yerel pre-Sonar blok yoğunluğu: Sonar öncesi kontroller kısa sürede çok fazla bağlantı engellerse, Sonar henüz saldırı bildirmemiş olsa bile TwiAntiVpn bunu saldırı sinyali gibi değerlendirir.

Yerel yoğunluk algılama ayarı:

```yaml
login-check:
  adaptive-sonar:
    pre-sonar-check-timeout-seconds: 6
    pre-sonar-block-spike:
      enabled: true
      count-blocks-within-seconds: 60
      trigger-after-blocked-connections: 15
```

Basit anlatımla varsayılan değer şudur: Sonar öncesi kontroller 60 saniye içinde 15 bağlantı engellerse tüm aktif kontroller Sonar sonrasına alınır. Böylece bot baskısını önce Sonar karşılar, TwiAntiVpn ise VPN, proxy, geo, ISP ve kullanıcı adı kurallarını Sonar doğrulamasından sonra uygulamaya devam eder.

Eski config anahtarları geriye dönük olarak desteklenir, fakat yeni configlerde `pre-sonar-block-spike`, `count-blocks-within-seconds` ve `trigger-after-blocked-connections` kullanılmalıdır.

## Komutlar

- `/twiantivpn help`
- `/twiantivpn reload`
- `/twiantivpn clear (<Oyuncu/UUID/IP>)`
- `/twiantivpn info <Oyuncu/UUID/IP>`
- `/twiantivpn whitelist <IP>`
- `/twiantivpn test <IP> [kullanıcı-adı]`
- `/twiantivpn test attack [on|off]`

`test <IP> [kullanıcı-adı]` aktif VPN/proxy, kullanıcı adı, geo ve ISP/ASN politikalarını kimsenin bağlantısını kesmeden değerlendirir. `test attack` mevcut modül sırasını gösterir; `on` saldırı sinyali simüle eder, `off` adaptive yönlendirmeyi toparlanma moduna geçirir.

`whitelist <IP>` IP adresini sunucu yeniden başlatılana kadar bellekte tutar ve TwiAntiVpn giriş kontrollerinin tamamından muaf kılar.

Aliaslar:

- `/twiavpn`
- `/tavpn`
- `/antivpn`

## Permissionlar

- `twiantivpn.command`
- `twiantivpn.command.help`
- `twiantivpn.command.reload`
- `twiantivpn.command.clear`
- `twiantivpn.command.info`
- `twiantivpn.command.test`
- `twiantivpn.command.whitelist`
- `twiantivpn.notify.vpn`
- `twiantivpn.notify.geo`
- `twiantivpn.notify.username`
- `twiantivpn.notify.isp`
- `twiantivpn.exemption.vpn`
- `twiantivpn.exemption.geo`

## Derleme

Gereksinimler:

- Derleme için Java 21
- Bu repodaki Gradle wrapper

Release jar dosyasını derlemek için:

```bash
./gradlew clean shadowJar
```

All-in-one jar `build/libs/` içine yazılır.

## Sürümleme

TwiAntiVpn tarih bazlı sürüm formatı kullanır:

```text
YYYY.MM.DD.build
```

Örnek:

```text
2026.07.09.26
```

## Katkı

Orijinal proje: `gerolndnr`.

Bakım: `siberanka`.

## Lisans

MIT
