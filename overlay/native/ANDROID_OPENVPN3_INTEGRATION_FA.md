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

The existing `RahYabVpnService.establishTun()` method accepts multiple IPv4/IPv6 addresses, routes, and DNS servers, but it is not yet called by a native TunBuilder callback adapter.

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

The pinned `OpenVPNClient` is itself derived from `TunBuilderBase`, `LogReceiver`, `ExternalTun::Factory`, and `ExternalTransport::Factory`. With the Android target's current feature macros, the two factory bases are empty; the subclass still must implement the abstract client callbacks and must not assume that JNI session APIs already exist.

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

The OpenVPNClient declaration inherits both factory base types, but the pinned forward headers define their pure virtual methods only when `OPENVPN_EXTERNAL_TUN_FACTORY` and `OPENVPN_EXTERNAL_TRANSPORT_FACTORY` are enabled. The Android CMake target intentionally leaves both definitions disabled, so those base classes are empty and do not add pure-virtual factory obligations in this build. If either feature macro is enabled later, the corresponding factory method must be implemented and the selected path re-audited. For the first supported profile subset, external PKI and custom transport remain unsupported and must be rejected during validation rather than silently accepted.

## Build strategy decision from source inspection

The pinned upstream `client/CMakeLists.txt` only creates the SWIG Python module when `BUILD_SWIG_LIB` is enabled; it does not define an Android JNI target. The pinned `ovpncli.hpp` is a client API header, not a self-contained implementation. A valid Android target therefore needs:
1. an Android-specific CMake target that compiles the upstream core/client implementation with the same feature definitions and dependency targets expected by `add_core_dependencies()`;
2. a small project-owned JNI translation unit that subclasses `ClientAPI::OpenVPNClient` and implements the pure-virtual callbacks above;
3. an Android TunBuilder adapter that translates the complete callback set to `VpnService.Builder`, including multiple addresses/routes/DNS and family policy;
4. a deliberate decision about the upstream external transport/tun factories, with unsupported profile features rejected until implemented.

No JNI library is claimed by this commit. The Android CMake target now compiles the pinned upstream `client/ovpncli.cpp` translation unit together with the project JNI translation unit and links the Android core dependencies in CI. This validates the native build path, not a working session. The next engineering gate is a concrete JNI client subclass plus a complete TunBuilder-to-`VpnService.Builder` adapter, followed by device-level tunnel tests.


## Latest integration decision and remaining blocker (2026-11-03)

The Android JNI CMake target no longer defines `OPENVPN_EXTERNAL_TUN_FACTORY`. This selects the upstream standard `TunBuilderBase` implementation path in `client/ovpncli.cpp`, which is the intended route for an Android `VpnService.Builder` adapter. This is a build-configuration decision only; it does not create a Java-to-native TUN bridge.

A further source-level detail was confirmed directly in the pinned headers:
- `OpenVPNClient` still inherits both `ExternalTun::Factory` and `ExternalTransport::Factory` in its class declaration, even when the external-TUN compile definition is not enabled.
- Their pure virtual methods remain part of the concrete subclass contract: `new_tun_factory(const Config &, const OptionList &)` and `new_transport_factory(const Config &)`.
- Consequently, removing `OPENVPN_EXTERNAL_TUN_FACTORY` does **not** by itself make a concrete subclass instantiable. The JNI client subclass must deliberately implement both factory methods and verify the standard transport/TunBuilder path used by this pinned build. Returning null or a dummy factory without checking the upstream call path would be unsafe.

The current CI run after this CMake change is run `41525113728`; it had completed SDK setup and source audit and was still building Android native dependencies at the last status check. The next gate is to let this build finish, then compile a minimal concrete subclass with explicit callback/factory implementations against the pinned headers before adding Java callbacks or claiming session support.

The earlier paragraph above describing `RahYabVpnService.establishTun()` as single-address/single-route/single-DNS is outdated: the service prototype now has a multi-address, multi-DNS, multi-route overload with IPv4/IPv6 handling. It is still not wired to OpenVPN's native callback interface.


## CI verification update (2026-11-03)

- Workflow run `41525113728` completed successfully, including Android dependency build, native core probe, complete `client/ovpncli.cpp` translation-unit compilation, JNI shared-library packaging, APK build, and APK verification.
- Workflow run `41525427592` also passed the native translation-unit compile step; at the time of this note it was completing artifact/report upload.
- The JNI source `jni_bridge.cpp` remains an intentional `JNI_OnLoad` placeholder. It does not instantiate `OpenVPNClient`, start a session, establish a TUN, or protect transport sockets.
- The service's multi-address/multi-DNS/multi-route helper is still not called by a native TunBuilder adapter.
- Therefore the current APK remains a build/integration scaffold, not a functioning standalone VPN.


## JNI compile-contract milestone (2026-11-03)

Commit `54b291cb3ec992cfff7c167224f1dc693a1f3fbb` adds a concrete `RahYabOpenVPNClient` subclass with the six pure callback overrides identified in the pinned `ovpncli.hpp`, plus a compile-time non-abstract assertion. This is an API-shape check only: it does not start a client, call `eval_config()`, establish TUN, protect sockets, or expose Java session controls. CI run `41527335666` is validating this change with the Android NDK.


## Android callback mapping audit (2026-11-03)

The complete pinned `openvpn/tun/builder/base.hpp` and `openvpn/client/dns_options.hpp` were inspected in full. The adapter must account for the following exact callbacks and semantics:

| OpenVPN callback | Android-side handling | Safety/compatibility rule |
|---|---|---|
| `tun_builder_new()` | Reset per-session builder state | Must reject concurrent reuse |
| `tun_builder_set_layer(3)` | TUN only | Reject TAP/layer values other than 3 |
| `tun_builder_set_remote_address()` | Record remote endpoint | Needed for diagnostics; do not treat as a substitute for socket protection |
| `tun_builder_add_address()` | Collect address + prefix | Preserve every IPv4/IPv6 address; validate family/prefix |
| `tun_builder_set_route_metric_default()` | Store default metric | Android Builder has no direct metric equivalent; document ignored metric semantics |
| `tun_builder_reroute_gw()` | Add IPv4 and/or IPv6 default route | Must add the requested family-specific default routes |
| `tun_builder_add_route()` | Collect included route | Preserve every route and prefix |
| `tun_builder_exclude_route()` | Collect excluded route | Android `excludeRoute(IpPrefix)` requires API 33; older Android must fail or use a separately implemented and tested emulation |
| `tun_builder_set_dns_options()` | Convert supported plain DNS addresses and search domains | Android Builder accepts numeric DNS IPs, not arbitrary DNS transports or DNS server ports |
| `tun_builder_set_mtu()` | Apply MTU | Validate supported range before establish |
| `tun_builder_set_session_name()` | Apply session label | Sanitize/limit label |
| `tun_builder_set_allow_family()` | Enforce family policy | Android's `allowFamily()` is not a general deny-family API; unsupported deny requests must not be silently treated as enforced |
| proxy callbacks | No proxy support in initial profile subset | Reject profiles requiring proxy callbacks |
| WINS callback | No WINS support in initial subset | Reject if required rather than ignore |
| `tun_builder_establish()` | JNI call into service; detach and return TUN fd | Return -1 on any Java exception or failed establish |
| `tun_builder_teardown()` | Close per-session TUN/clear builder state | Must be idempotent and respect native fd ownership |

### DNS conversion constraints

Pinned `DnsOptions` contains a priority-keyed map of `DnsServer` values. Each server may include multiple `DnsAddress` entries (address and optional port), domains, DNSSEC mode, transport (Plain/HTTPS/TLS), and SNI; global search domains are also present.

The first Android implementation can safely map only numeric plain DNS addresses with an Android-compatible port (default or 53), plus search domains supported by `VpnService.Builder.addSearchDomain()`. DNS-over-HTTPS, DNS-over-TLS, nonstandard DNS ports, DNSSEC enforcement, and SNI cannot be represented by `addDnsServer(InetAddress)` alone. Such settings must be explicitly rejected until a resolver implementation exists; silently dropping them would change profile security semantics.

### Current code gap

The current native subclass only implements the six abstract `OpenVPNClient` callbacks. It does not yet override the TunBuilder callbacks listed above. Since several `TunBuilderBase` defaults return `false` or `-1`, relying on those defaults causes tunnel setup to fail; changing them to unconditional success would be equally unsafe. The next code change must introduce a per-session stateful adapter and only report success after Android has accepted the corresponding setting.

### Android API reference

Android documents that `VpnService.Builder.establish()` creates the TUN descriptor and that the application must exchange packets through it; `VpnService.protect(int)` keeps the transport socket outside the VPN route to prevent a loop. See:
- https://developer.android.com/reference/android/net/VpnService
- https://developer.android.com/develop/connectivity/vpn


## SWIG/Java binding feasibility audit (2026-11-03)

The pinned upstream SWIG interface was inspected at the exact OpenVPN 3 source pin:

- `client/ovpncli.i` declares `%module(directors="1") ovpncli`.
- It enables directors for `OpenVPNClient` specifically with `%feature("director") OpenVPNClient;`.
- It renames and exposes `TunBuilderBase`, `DnsOptions`, and the client API types to SWIG.
- Under `SWIGJAVA`, it additionally includes `swiginterface.i` and declares `LogReceiver` as a Java interface.
- The pinned repository tree contains only one Android-specific source file, `javacli/android/cpu.cpp`; that file exports a CPU-usage JNI method and is not an OpenVPN session wrapper.
- The pinned `client/CMakeLists.txt` only defines a Python SWIG target when `BUILD_SWIG_LIB` is enabled. It does not define an Android Java/JNI target.

### Engineering conclusion

The existence of `ovpncli.i` is not sufficient evidence that the pinned upstream release provides a ready-to-build Android Java binding. In particular, its explicit director declaration covers `OpenVPNClient`; it does not explicitly enable directors for `TunBuilderBase`. The Java-specific include `swiginterface.i` also needs to be resolved and validated for this exact source/build environment before relying on it.

Do not switch the app to SWIG or mark the engine available based only on generated proxy classes. Before adopting SWIG, a CI proof must:
1. resolve every SWIG Java include from the pinned source/toolchain;
2. generate Java proxies and the JNI wrapper using the pinned `ovpncli.i`;
3. compile and link the generated wrapper for Android arm64-v8a;
4. prove that Java overrides of the required `TunBuilderBase` callbacks are actually dispatched from native OpenVPN code;
5. prove that `OpenVPNClient` lifecycle, event/log callbacks, and socket protection can be driven safely from Java.

If any of these gates fails, retain a project-owned JNI adapter rather than shipping a partial SWIG binding. The current app remains unavailable as an embedded VPN until the complete path is implemented and device-tested.


## Conditional external factory API correction (2026-11-03)

A follow-up inspection of the exact pinned headers `openvpn/tun/extern/fw.hpp` and `openvpn/transport/client/extern/fw.hpp` corrects an earlier overly broad note about factory methods:

- `ExternalTun::Factory::new_tun_factory()` is pure virtual only when `OPENVPN_EXTERNAL_TUN_FACTORY` is defined.
- `ExternalTransport::Factory::new_transport_factory()` is pure virtual only when `OPENVPN_EXTERNAL_TRANSPORT_FACTORY` is defined.
- The current project JNI CMake target does not define either macro. In this build configuration, both Factory base classes are empty and these methods are not required overrides for the subclass.
- If either macro is enabled in a future build, the corresponding real factory implementation becomes mandatory; do not add dummy/null factory implementations to satisfy compilation.

Therefore the immediate JNI blocker is not those factory methods in the current configuration. It is the missing stateful TunBuilder callback bridge, session lifecycle, and fail-closed socket protection. The exact pinned `OpenVPNClient` interface confirms `eval_config()`, `provide_creds()`, `connect()`, `stop()`, and `connection_info()` are the intended lifecycle calls; `connect()` is blocking and must run on a managed worker thread. `socket_protect()` is a virtual callback invoked by the connect thread and must synchronously delegate to Android `VpnService.protect(fd)`, returning false if protection fails.


## Native TunBuilder collector milestone (2026-11-03)

Commit `64ce7911d0a05f0bd191246c279f4ad3beda27b9` adds a per-client native configuration collector to `RahYabOpenVPNClient`. It now overrides the pinned TunBuilder callbacks for:
- TUN layer validation (layer 3 only);
- remote endpoint recording;
- multiple IPv4/IPv6 interface addresses;
- IPv4/IPv6 default routes and explicit include/exclude routes;
- route metric, MTU, and session name;
- builder reset and teardown.

This is intentionally **not** a live Android TUN bridge. `tun_builder_establish()` still returns `-1`, so OpenVPN cannot mistake an unimplemented Java callback for a valid tunnel. DNS options are rejected until the first supported plain-DNS/search-domain mapping is implemented. Address-family deny requests are rejected rather than silently ignored.

The commit is on `v2-engine-starter`; `main` is unchanged. CI has not yet validated this new commit, so no APK is being offered from it. The next gate is an NDK compile of the callback overrides, followed by the JNI-to-service dispatcher and synchronous `VpnService.protect(fd)` bridge.


## Verified adapter correction (2026-11-03)

The pinned `openvpn/tun/builder/base.hpp` specifies that route metrics are ignored whenever their value is less than zero. The Android adapter therefore now accepts any negative metric as “unspecified” and normalizes it to `-1`; non-negative explicit metrics remain rejected because Android's `VpnService.Builder` does not expose equivalent route-metric control. This avoids rejecting valid upstream defaults while preserving fail-closed behavior for unsupported explicit metrics.

- Source commit: `bd6f730cbf28006f2441ecd4d7f5c8f08bfa77ab`
- CI run: https://github.com/mohsenkhob-cpu/EmergencyConnect/actions/runs/41578753891 (success)
- This is a callback-semantics correction and native/Gradle build verification only. The native session worker, Java event bridge, foreground-service lifecycle, and live device tunnel validation remain outstanding. 
