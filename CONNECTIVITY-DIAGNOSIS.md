# Filter Mohsen – connectivity audit, 19 Nov 2026

Based on first-party on-device reports and the current filter-mohsen source. Building an APK does not establish real connection success.

* OpenVPN: one UDP VPN Gate relay (133.32.219.138:7060) previously delivered 14 stable samples and usable YouTube, then later relays could not be verified. Verified profile settings are now stored under AndroidKeyStore AES-GCM encryption for up to 24 hours per physical network transport and retried before fresh volunteer candidates. No plaintext credentials are logged or stored by this new feature.
* SSTP: the user experienced about 5 minutes of functioning traffic. Keep the existing motor unchanged while availability remains dependent on volunteer server behavior.
* Xray: HTTPS verification now reports a categorized probe failure (DNS, timeout, network path) rather than a generic failure alone. No tunnel is claimed healthy without a verified page.
* sing-box: generated grouped and single-server profiles now include a 'direct' outbound for bootstrap DNS; it is excluded from VPN relay testing. Android TUN setup reports route/DNS/address counts and conditionally supplies missing route/DNS only for generated profiles. Custom imports are left intact.
* WireGuard: server public key, client private key and a reachable peer are necessary; a web page is not a valid .conf profile. A failed handshake is a server/configuration signal, not necessarily a broken native engine.

Primary references:
https://developer.android.com/develop/connectivity/vpn
https://sing-box.sagernet.org/configuration/dns/server/https/
https://www.vpngate.net/en/about_faq.aspx
https://www.wireguard.com/quickstart/

Regression: run unit tests and package verification; then separately test each engine on Wi-Fi and mobile data, verify YouTube in Chrome, 15-minute persistence, stop/disconnect, DNS and exit IP.
