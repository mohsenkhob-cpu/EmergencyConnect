# Filter Mohsen — network-aware connectivity review (2026-11-19)

## Real device observations
Samsung SM-A155F, Android 17, build 1.0.20:
- Wi-Fi Mokhaberat: OpenVPN established a VPN tunnel, exit IP changed and YouTube was actually opened by the user; Xray sometimes passed HTTPS checks but the user could not watch YouTube.
- IR-MCI mobile data: SSTP successfully opened YouTube, while a 12-relay OpenVPN sample never reached a confirmed connection.
- sing-box: the TUN established on both networks with one address, one DNS server and one route, but neither DNS via the VPN nor the independent HTTPS-over-IP probe worked.
- SSTP performance samples can underestimate real browsing and video behavior; a tunnel must not be torn down merely because one short sample is slow.
- WireGuard source links require genuine key-bearing, reachable peer configurations, not arbitrary publicly accessible URLs.

## Changes in 1.0.21
1. Quick Connect follows observed network-specific success: IR-MCI tries SSTP then OpenVPN. Other networks keep OpenVPN then SSTP. An unknown network is not assumed to behave like IR-MCI. The same app still contains sing-box and optional WireGuard.
2. SSTP preserves the first truly verified HTTPS tunnel instead of disconnecting it to chase a speculative faster relay. The last verified SSTP host is remembered for six hours, tagged by physical transport, and checked early in the next search. Only validated opengw.net hosts are accepted.
3. Xray reports HTTPS/page access as **limited** rather than falsely indicating YouTube video playback. The UI uses an amber indicator. Historical HTML-only nodes are not ranked as proven video relays. Real playback must be tested on device.
4. sing-box checks at most two numeric-IP relay TCP sockets (protected from VPN loops) before creating the tunnel. Its new diagnostic is one of RELAY_TCP_REACHABLE, RELAY_TCP_UNREACHABLE_N_TESTS, RELAY_TCP_HOSTNAME_NEEDS_DNS, RELAY_TCP_PROTECT_FAILED, or RELAY_TCP_NO_COMPATIBLE_ENDPOINT. It never includes keys, IPs or hostnames. Failure is *diagnostic*, not an automatic disqualification.
5. Added deterministic JVM unit tests for MCI order, other networks, validated SSTP hosts and duplicate elimination.

## Research comparison
- Hiddify's automatic outbound monitoring keeps cached results, rechecks on network changes, and differentiates failed paths. Its sing-box core can use WARP/Psiphon; our app does **not** include that infrastructure.
- v2rayNG and Hiddify require valid server configurations for proxy protocols; they cannot guarantee untrusted public lists will remain up.
- Proton VPN Free is an operated free VPN service with no data cap, not merely a public server list.
- Psiphon dynamically varies transports and selects among its own servers. These features would require a significant separate integration, not a trivial app-side patch.
- Android VpnService requires protecting upstream sockets, routing, DNS setup and real path verification. A successfully created TUN is not proof of internet reachability.
- sing-box 1.14 DNS-over-HTTPS dialer semantics differ from older configs, and Android platform integration has specific requirements. Avoid assuming that a direct dialer or TUN creation equals usable internet.

## References
- https://hiddify.com/core/features/groups/monitoring/
- https://github.com/hiddify/hiddify-app
- https://github.com/2dust/v2rayNG
- https://psiphon.ca/en/faq
- https://protonvpn.com/free-vpn
- https://developer.android.com/develop/connectivity/vpn
- https://sing-box.sagernet.org/configuration/dns/server/https/
- https://sing-box.sagernet.org/configuration/inbound/tun/

## Remaining uncertainties
The current branch has only been built and unit-tested on GitHub, not verified on this physical device. Short-lived anonymous relay servers may be blocked, shut down or misconfigured. Different Android carrier networks can behave differently. A user-reported successful YouTube playback is stronger evidence of functional media access than an HTTP 200 or 204 test. The application must not promise uninterrupted VPN access.
