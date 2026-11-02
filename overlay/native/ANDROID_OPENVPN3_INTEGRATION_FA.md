# OpenVPN 3 source review and Android integration contract

## Exact upstream revision selected
- Repository: https://github.com/OpenVPN/openvpn3
- Commit: `0abc8313b8fe5f63dbebc10d53094952957845d9` (2026-10-29)
- CI checks out this exact commit detached and verifies the resulting SHA.

## Relevant upstream API and build facts
- Public client API: `client/ovpncli.hpp`; implementation: `client/ovpncli.cpp`.
- `client/ovpncli.i` is a SWIG interface. Its checked-in CMake target is configured for Python, not a ready-made Android JNI library.
- The upstream root project requires C++20. `add_corelibrary_dependencies()` compiles core implementation sources into the consuming target and resolves patched standalone Asio, LZ4, fmt, and OpenSSL (or mbedTLS). It is not a single self-contained library target.
- The upstream root CMake also adds desktop agents and test targets. Android integration must use a deliberately scoped Android target rather than blindly adding the upstream root project.
- Android/iOS builds select the `USE_TUN_BUILDER` path in `client/ovpncli.cpp` unless an external TUN factory is selected.

## TunBuilderBase contract reviewed at the pinned revision
The upstream header is `openvpn/tun/builder/base.hpp`. Its virtual interface includes:
- lifecycle: `tun_builder_new()`, `tun_builder_establish()`, `tun_builder_teardown(bool)`;
- addresses: `tun_builder_set_remote_address()`, `tun_builder_add_address()`;
- routes: `tun_builder_set_route_metric_default()`, `tun_builder_reroute_gw()`, `tun_builder_add_route()`, `tun_builder_exclude_route()`;
- DNS and interface policy: `tun_builder_set_dns_options()`, `tun_builder_set_allow_family()`, `tun_builder_set_allow_local_dns()`;
- interface properties: `tun_builder_set_mtu()`, `tun_builder_set_session_name()`;
- optional proxy/WINS and persistence callbacks.
Most base methods have default implementations; the Android adapter must override the operations needed by the profiles it accepts and must return failure for unsupported required operations rather than silently claiming success.

## Required Android adapter mapping
The native adapter must implement `ClientAPI::TunBuilderBase` and marshal configuration to a live `RahYabVpnService` instance:
1. Collect every IPv4/IPv6 address, route, DNS server, MTU, session name, and allowed address family during builder callbacks. Do not reduce the configuration to one IPv4 address/route/DNS entry.
2. At `tun_builder_establish()`, call Android `VpnService.Builder` through JNI on the Android service context; apply all collected values and call `establish()`.
3. Return the detached TUN file descriptor to OpenVPN 3. Native code owns that descriptor after return and must close it exactly once at teardown.
4. Call `VpnService.protect(fd)` for each OpenVPN transport socket before it can be routed through the VPN TUN. A failure to protect a socket must fail the connection setup.
5. Implement teardown, service stop/revoke, worker-thread cancellation, and callback lifetime without calling Java on a destroyed Activity.
6. Run the OpenVPN client on a worker thread. Forward state, event, log, and error callbacks to Java with explicit thread attachment and safe global/local JNI reference handling.

## JNI/session contract to implement
The current Java `OpenVpn3Bridge` declarations are placeholders only. The eventual native layer must provide:
- a stable native session handle with explicit ownership and idempotent stop;
- profile/config validation and credential/challenge callbacks;
- OpenVPN client event/status/log forwarding;
- worker-thread startup and join/cancellation;
- a per-session TunBuilder object holding a weak or explicitly cleared Java service reference;
- exception translation (clear pending JNI exceptions and return a structured error);
- no "CONNECTED" state until OpenVPN reports the connected event and the TUN is established.

The existing `RahYabVpnService.establishTun()` method is only a first TUN ownership prototype. It currently accepts a single IPv4 address, one route, and one DNS server. It is not yet the complete callback adapter and must be expanded before native integration.

## Android build plan
- Use Android NDK + CMake with C++20 and explicit ABI builds (initially `arm64-v8a`; add `armeabi-v7a` only after the first ABI is stable).
- Build the required upstream core source units into the JNI shared library, resolving pinned dependency revisions and Android-compatible OpenSSL/LZ4/fmt/Asio targets.
- Do not link desktop test/agent targets.
- Generate or write the JNI layer against the actual C++ API; do not treat the upstream Python SWIG target as an Android binding.
- Package `libopenvpn3_jni.so` into the APK and verify its exported JNI symbols and runtime load.

## Release gates
A successful Gradle build alone is not a VPN test. Do not call the app standalone until all are true:
- APK contains the native library for each claimed ABI;
- OpenVPN profile parsing and TLS handshake succeed against a controlled test endpoint;
- bidirectional packets, DNS, IPv4 and IPv6 policy, and socket protection are verified on a device;
- stop/revoke/process-death cleanup has no leaked fd or worker thread;
- reconnect and server failover are exercised under deliberate network loss;
- license choice and notices/source obligations are satisfied.

## License
Upstream `LICENSE.md` offers MPL-2.0 or AGPL-3.0-only with an OpenSSL exception. The project must select and comply with a distribution path and ship required notices/source availability before public distribution.

## Current status
The pinned upstream API and license material are audited and archived by CI. The Android VPN service and TUN fd ownership are scaffolds. The JNI bridge, complete TunBuilder callback adapter, Android NDK dependency build, linked native core, foreground service lifecycle, and on-device traffic tests are not implemented yet. The generated APK is therefore not an independent VPN client.


## Client API details verified from ovpncli.hpp

The pinned `OpenVPNClient` is itself derived from `TunBuilderBase`, `LogReceiver`, `ExternalTun::Factory`, and `ExternalTransport::Factory`. The integration must subclass it (or use an equivalent concrete adapter) and implement the required abstract callbacks, not merely call a nonexistent standalone JNI entry point.

Verified lifecycle/API sequence:
1. Construct a client/session object and call `eval_config(Config)`; reject profiles whose returned `EvalConfig.error` is true.
2. Supply credentials with `provide_creds(ProvideCreds)` when required.
3. Run `connect()` on a dedicated worker thread. The API explicitly says this call blocks until disconnect.
4. Implement `socket_protect(openvpn_io::detail::socket_type, remote, ipv6)`; it must call Java `VpnService.protect(fd)` and propagate failure.
5. Forward `event(...)` and `log(...)` callbacks safely to Java. Do not infer connection from worker startup; use the actual CONNECTED event.
6. Stop asynchronously through `stop()`, then join the worker before destroying the client/session object. `pause()`, `resume()`, and `reconnect(seconds)` are available for network lifecycle management.

The API also exposes `connection_info()` after CONNECTED, `stats_bundle()`, and helper APIs such as `OpenVPNClientHelper::merge_config_string()` and `eval_config()`. JNI implementation must be written against the exact declarations in the pinned header and must not invent signatures.

These findings refine the implementation contract; they are not an implementation. No native target, JNI callbacks, or live tunnel have been added by this review.

## Additional pure-virtual requirements confirmed from the pinned headers

Inspection of `client/ovpncli.hpp`, `openvpn/tun/extern/fw.hpp`, and `openvpn/transport/client/extern/fw.hpp` confirms that a concrete client subclass must implement these OpenVPNClient callbacks:
- `pause_on_connection_timeout()`;
- `event(const Event &)`;
- `acc_event(const AppCustomControlMessageEvent &)`;
- `log(const LogInfo &)`;
- `external_pki_cert_request(ExternalPKICertRequest &)`;
- `external_pki_sign_request(ExternalPKISignRequest &)`.

The inherited factory interfaces also require:
- `ExternalTun::Factory::new_tun_factory(const Config &, const OptionList &)`;
- `ExternalTransport::Factory::new_transport_factory(const Config &)`.

This is important for the implementation design: even a profile that does not use external PKI or custom transport must provide deliberate callback behavior, and the default factory cannot be assumed to exist. For the first supported profile subset, unsupported external-PKI and custom transport requests should be rejected during validation rather than silently accepted. The Android adapter should use the standard OpenVPN transport path unless a concrete transport factory is intentionally implemented.

## Build strategy decision from source inspection

The pinned upstream `client/CMakeLists.txt` only creates the SWIG Python module when `BUILD_SWIG_LIB` is enabled; it does not define an Android JNI target. The pinned `ovpncli.hpp` is a client API header, not a self-contained implementation. A valid Android target therefore needs:
1. an Android-specific CMake target that compiles the upstream core/client implementation with the same feature definitions and dependency targets expected by `add_core_dependencies()`;
2. a small project-owned JNI translation unit that subclasses `ClientAPI::OpenVPNClient` and implements the pure-virtual callbacks above;
3. an Android TunBuilder adapter that translates the complete callback set to `VpnService.Builder`, including multiple addresses/routes/DNS and family policy;
4. a deliberate decision about the upstream external transport/tun factories, with unsupported profile features rejected until implemented.

No JNI library is claimed by this commit. The next engineering gate is to establish a minimal reproducible Android CMake compilation of the pinned upstream client/core sources before implementing JNI behavior. This avoids creating a misleading placeholder shared library that merely exports JNI names without a working OpenVPN core.


## Latest integration decision and remaining blocker (2026-11-03)

The Android JNI CMake target no longer defines `OPENVPN_EXTERNAL_TUN_FACTORY`. This selects the upstream standard `TunBuilderBase` implementation path in `client/ovpncli.cpp`, which is the intended route for an Android `VpnService.Builder` adapter. This is a build-configuration decision only; it does not create a Java-to-native TUN bridge.

A further source-level detail was confirmed directly in the pinned headers:
- `OpenVPNClient` still inherits both `ExternalTun::Factory` and `ExternalTransport::Factory` in its class declaration, even when the external-TUN compile definition is not enabled.
- Their pure virtual methods remain part of the concrete subclass contract: `new_tun_factory(const Config &, const OptionList &)` and `new_transport_factory(const Config &)`.
- Consequently, removing `OPENVPN_EXTERNAL_TUN_FACTORY` does **not** by itself make a concrete subclass instantiable. The JNI client subclass must deliberately implement both factory methods and verify the standard transport/TunBuilder path used by this pinned build. Returning null or a dummy factory without checking the upstream call path would be unsafe.

The current CI run after this CMake change is run `41525113728`; it had completed SDK setup and source audit and was still building Android native dependencies at the last status check. The next gate is to let this build finish, then compile a minimal concrete subclass with explicit callback/factory implementations against the pinned headers before adding Java callbacks or claiming session support.

The earlier paragraph above describing `RahYabVpnService.establishTun()` as single-address/single-route/single-DNS is outdated: the service prototype now has a multi-address, multi-DNS, multi-route overload with IPv4/IPv6 handling. It is still not wired to OpenVPN's native callback interface.
