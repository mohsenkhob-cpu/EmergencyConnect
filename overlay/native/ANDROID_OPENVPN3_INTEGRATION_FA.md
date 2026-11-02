# Android OpenVPN 3 integration plan

## Verified baseline
- The project ZIP contains a Java VPN Gate browser/import flow. It exports an .ovpn profile to an external OpenVPN app.
- The extracted `OpenVpn3Bridge` declares native methods but no JNI implementation is present.
- The extracted `VpnEngine` is an interface/contract, not a running engine.
- The overlay adds an Android `VpnService` and a TUN Builder adapter scaffold. This is not yet a working VPN client.

## Upstream integration reference
Use the official [OpenVPN/openvpn3](https://github.com/OpenVPN/openvpn3) client library, not a repackaged OpenVPN 2 executable. The upstream client API is `client/ovpncli.hpp`; Android/iOS use `TunBuilderBase` for OS-managed tunnel creation. Pin a reviewed upstream commit before vendoring it; do not track a moving branch in a reproducible release.

## Required implementation sequence
1. Record the exact upstream commit, license files, bundled dependencies, and notices. OpenVPN 3 source files declare MPL-2.0 OR AGPL-3.0-only WITH OpenVPN's OpenSSL exception; determine the distribution obligations before publishing binaries.
2. Add Android NDK/CMake toolchain and build the upstream C++ client for each supported ABI. Do not set `isAvailable=true` until the packaged shared library is present and successfully loaded.
3. Implement JNI session ownership around `ClientAPI::OpenVPNClient`, including start/stop, callbacks, logs, state/error reporting, and safe thread shutdown.
4. Implement a complete `TunBuilderBase` adapter: IPv4/IPv6 addresses, routes, DNS, MTU, allowed/disallowed applications as applicable, establish/teardown, and socket protection. Handle every callback or explicitly reject unsupported options.
5. Implement the Android foreground-service lifecycle, VPN consent via `VpnService.prepare()`, notification channel, network changes, and stop/revoke handling.
6. Implement packet/TUN descriptor ownership and I/O. The descriptor returned by `establishTun()` is detached; the native owner must close it exactly once.
7. Add instrumentation and device tests: successful handshake, traffic in both directions, DNS, IPv4/IPv6 routing, reconnect, server failover, cancellation, process death, and leak checks.

## Current descriptor contract
`RahYabVpnService.establishTun()` returns a detached file descriptor. Java no longer owns that descriptor after return. The native caller is responsible for closing it exactly once. A negative return indicates that Android did not establish the TUN interface.

## Release gate
A successful Gradle build only proves that the Android scaffold compiles. Do not label an APK standalone or connected until native ABI libraries are packaged and a real device completes a verified OpenVPN handshake and routes traffic through the tunnel.
