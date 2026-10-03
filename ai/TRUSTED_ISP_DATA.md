# Trusted Local ISP Data

Source of truth in code: `core/src/main/java/com/siberanka/twiantivpn/core/vpn/TrustedResidentialIsps.java`.

## Current snapshot

- Data date: APNIC Labs per-AS user population, 60-day window ending 2026-09-30.
- Countries: TR, AZ, KZ, UZ, KG, TM.
- Entries: 168 ASNs (TR 29, AZ 49, KZ 46, UZ 24, KG 15, TM 5).

## Method

1. Download APNIC Labs "Estimates of user population per AS" per country (JSON):
   `https://stats.labs.apnic.net/cgi-bin/aspop?c=<CC>&f=j`.
   License note from the dataset: "(C) APNIC Pty/Ltd. Re-use with attribution permitted".
   The attribution is in the README credits and the class Javadoc.
2. Keep ASNs with at least 0.05% of the country's measured users.
3. Verify the RIR registration country with RIPEstat
   (`https://stat.ripe.net/data/rir-stats-country/data.json?resource=AS<n>`); it must match.
4. Remove networks that are not local access ISPs:

| ASN | Network | Reason |
|---|---|---|
| AS13335 | Cloudflare | CDN / WARP egress |
| AS199524, AS202422 | G-Core Labs | Hosting / CDN |
| AS14593 | SpaceX Starlink | Global satellite operator, not local |
| AS137409 | GSL Networks | Foreign hosting, already in the ISP block list |
| AS212238 | Datacamp (CDN77) | Hosting / CDN, already in the ISP block list |
| AS213541 | WS Telecom | Foreign network |
| AS213535 | YottaSrc | Hosting |
| AS214095 | E Sim international | eSIM / roaming egress |
| AS218687 | Vsem Network | Unclear foreign network |
| AS216472 | High Speed For Internet Services (HS-SYR) | Cross-border network |
| AS202254 | Lifecell Digital | Unclear access role |
| AS43242 | Arinet Security & Internet Consultancy | Unclear access role |
| AS8517 | ULAKNET | Academic network (hosts servers) |
| AS29584 | AZEDUNET | Academic network |
| AS206977 | AZ state special communication service | State network |
| AS209489 | Azintex.com | Hosting / datacenter |
| AS196925 | Azertelecom | Backbone / wholesale with datacenter services |
| AS202293 | D-Cloud | Cloud provider |

5. Check that no entry overlaps `provider.isp-block.asns` (enforced by `TrustedResidentialIspsTest`).

## Corrections compared with the 2026-09-17 list

The previous 34-entry list missed the largest access networks in several countries (for example
AS8814 Aztelekom, AS206026 Kar-Tel/Beeline KZ, AS29555 Altel, AS8193 Uzbektelecom, AS41202
UNITEL, AS64466 UMS, AS47237 and AS50251 NUR Telecom, AS29061 Saima Telecom, AS59974 TMCELL,
AS34296 Millenicom, AS12978 D-Smart). AS28910 was removed because it has no measurable end users.

## Refresh checklist

- Re-run the method, update the class, the counts in this file, the README and the tests
  (`VpnAsnWhitelistServiceTest`, `ConnectionGuardVpnAsnWhitelistTest`, `TrustedResidentialIspsTest`).
- Keep the exclusion table in sync with any new manual exclusions.
