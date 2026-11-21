# Fluxcore and Aether integration (work in progress)

Requested behavior:
- Retire the **standalone** WireGuard and the inactive Psiphon entry.
- Add real, independently selectable **Fluxcore** and **Aether** cores.
- Preserve verified SSTP/OpenVPN/Xray behavior until replacements pass on-device tests.
- Prioritize the engines only after APK includes native binaries and the complete TUN/data plane is operational.
- User tested both Niloofar cores successfully on MCI cellular data and Mokhaberat Wi-Fi, Samsung A15, Android 17.

## What has changed on this isolated feature branch

- The old standalone WireGuard buttons/import guidance are hidden.
- WireGuard is removed from the auto-connect priority order; tests updated.
- The inactive Psiphon status label is removed.
- Legacy WireGuard implementation is retained temporarily in source to avoid breaking old lifecycle code during the transition.
- Fluxcore and Aether now have a separately implemented Java VpnService, a native SOCKS5 HTTPS probe, and a dedicated upstream tun2socks bridge.
- The CI builds pinned Fluxcore from MIT source, packages the official AGPL Aether ARM64 release with matching release checksum, and builds the MIT TUN bridge from source.
- **Android instrumentation and on-device VPN connectivity have NOT yet been validated. APK build success must not be confused with a working tunnel.**

## Upstream sources and licenses

- Fluxcore: https://github.com/FossilizedProgrammer/fluxcore — MIT, with CumulusVPN NOTICE.
- Aether: https://github.com/CluvexStudio/Aether — AGPL-3.0. Review distribution obligations for an APK that bundles the standalone engine and supply complete corresponding source and license information before distributing.
- tun2socks: https://github.com/heiher/hev-socks5-tunnel — MIT.
- Niloofar reference: https://github.com/FossilizedProgrammer/niloofar_vpn — contains Android integrations, but repository itself has no declared root license. Do not copy Niloofar Java/Kotlin or binaries without separate permission or an applicable license; implement our own Android integration using the licensed upstreams above.
- Do not simply copy binary .so files from an unrelated APK.

## Engineering work required

1. Pin known-good source revisions; build ARM64 Android executables (Fluxcore with Go/cgo and Android NDK; Aether from its upstream with reproducible provenance).
2. Package an MIT-licensed Android ARM64 tun2socks JNI library and Java bridge.
3. Implement a foreground VpnService with one active native engine, its local SOCKS5 listener, Android VpnService.Builder TUN, traffic forwarding, DNS, IPv4/IPv6 routing, and self-app bypass/protected sockets to avoid loops.
4. For Fluxcore use its own WireGuard/AmneziaWG transport **internally**; removing the old stand-alone WireGuard UI must not disable Fluxcore's transport.
5. For Aether attempt safe supported upstream transport settings (e.g. MASQUE H2 then H3); never disable TLS certificate validation by default.
6. Only mark connected after verified end-to-end data-plane traffic *through the actual new engine*, not after native process start or a local listening port alone.
7. Implement cancellation, lifecycle cleanup, quality monitoring and bounded reconnection. Avoid leaking private configurations or credentials in logs.
8. Integrate two independent buttons and optional quick-connect fallback **after** native provisioning and runtime tests pass; leave SSTP/OpenVPN/Xray as fallbacks.
9. Remove old WireGuard dependency, service, sources and unused code only after all call sites and tests have been migrated; avoid changing shipped branch prematurely.

## Acceptance test matrix

- Unit and instrumentation tests, Android Gradle compile, APK signature and ABI, process/JNI verification.
- Install and launch on Android 17, SM-A155F. Check MCI and Mokhaberat Wi-Fi **separately** for both cores.
- Test DNS resolution, HTTPS website access, video playback, continuous traffic, 15+ min stability, repeated connect/disconnect, Wi-Fi -> mobile transitions and clean fallback.
- Verify no VPN/TUN traffic escapes through the physical network when the connection claims to be active, and ensure the app itself doesn't loop its own transport sockets.
- No false success screen and no placeholder buttons.

**Status: feature branch builds the full native integration. It requires a successful final CI run and real handset tests before any claim of connection quality or production readiness.**
