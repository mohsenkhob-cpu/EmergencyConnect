#include <jni.h>
#include <openssl/evp.h>
#include <arpa/inet.h>

#include <exception>
#include <mutex>
#include <memory>
#include <thread>
#include <string>
#include <type_traits>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <vector>

#include "ovpncli.hpp"

// Compile-time contract check for the pinned OpenVPN 3 client API.
// Android TunBuilder callbacks and the managed native worker are implemented;
// Java exposes this path for explicitly labeled experimental device testing only.
namespace {

JavaVM *g_vm = nullptr;
jobject g_vpn_service = nullptr;
jmethodID g_establish_tun = nullptr;
jmethodID g_protect_socket = nullptr;
jmethodID g_native_event = nullptr;
std::mutex g_service_mutex;

class ScopedJniEnv {
  public:
    ScopedJniEnv() : env_(nullptr), attached_(false) {
        if (!g_vm) return;
        const jint state = g_vm->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6);
        if (state == JNI_EDETACHED &&
            g_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        } else if (state != JNI_OK) {
            env_ = nullptr;
        }
    }
    ~ScopedJniEnv() {
        if (attached_ && g_vm) g_vm->DetachCurrentThread();
    }
    JNIEnv *get() const { return env_; }
  private:
    JNIEnv *env_;
    bool attached_;
};

jobject acquire_service(JNIEnv *env, jmethodID *establish = nullptr,
                        jmethodID *protect = nullptr) {
    std::lock_guard<std::mutex> lock(g_service_mutex);
    if (establish) *establish = g_establish_tun;
    if (protect) *protect = g_protect_socket;
    return g_vpn_service ? env->NewLocalRef(g_vpn_service) : nullptr;
}

jobjectArray make_string_array(JNIEnv *env, const std::vector<std::string> &values) {
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (!array) return nullptr;
    for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
        jstring item = env->NewStringUTF(values[static_cast<size_t>(i)].c_str());
        if (!item) { env->DeleteLocalRef(array); return nullptr; }
        env->SetObjectArrayElement(array, i, item);
        env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(array); return nullptr; }
    }
    return array;
}

jintArray make_int_array(JNIEnv *env, const std::vector<jint> &values) {
    jintArray array = env->NewIntArray(static_cast<jsize>(values.size()));
    if (!array) return nullptr;
    if (!values.empty()) {
        env->SetIntArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(array);
            return nullptr;
        }
    }
    return array;
}

class RahYabOpenVPNClient final : public openvpn::ClientAPI::OpenVPNClient {
  public:
    struct Address {
        std::string value;
        int prefix;
        bool ipv6;
        std::string gateway;
        bool net30;
    };
    struct Route {
        std::string value;
        int prefix;
        int metric;
        bool ipv6;
        bool excluded;
    };

    // Per-client callback state collected before Android establishes the TUN.
    // Unsupported Android Builder semantics fail closed instead of being dropped.
    std::string remote_address;
    bool remote_ipv6 = false;
    std::string session_name = "RahYabVPN";
    int mtu = 0;
    int default_route_metric = -1;
    std::vector<Address> addresses;
    std::vector<Route> routes;
    bool builder_active = false;
    bool unsupported_option_seen = false;
    std::vector<std::string> dns_servers;
    std::vector<std::string> search_domains;

    bool tun_builder_new() override {
        if (builder_active) {
            return false;
        }
        remote_address.clear();
        session_name = "RahYabVPN";
        mtu = 0;
        default_route_metric = -1;
        addresses.clear();
        routes.clear();
        dns_servers.clear();
        search_domains.clear();
        unsupported_option_seen = false;
        builder_active = true;
        reportDiagnostic("TUN_BUILDER_BEGIN",
                "OpenVPN supplied tunnel parameters; preparing Android TUN.", false, false);
        return true;
    }

    bool tun_builder_set_layer(int layer) override {
        return layer == 3;
    }

    bool tun_builder_set_remote_address(
            const std::string &address, bool ipv6) override {
        if (!builder_active || address.empty() || address.size() > 64) return false;
        remote_address = address;
        remote_ipv6 = ipv6;
        return true;
    }

    bool tun_builder_add_address(
            const std::string &address, int prefix, const std::string &gateway,
            bool ipv6, bool net30) override {
        const int max_prefix = ipv6 ? 128 : 32;
        if (!builder_active || address.empty() || address.size() > 64 ||
            addresses.size() >= 32 || prefix < 0 || prefix > max_prefix) {
            return false;
        }
        // Android VpnService.Builder.addAddress() accepts the local address
        // and prefix, but has no peer/gateway parameter. OpenVPN 3 supplies
        // gateway separately while tun_builder_add_route()/reroute_gw()
        // supplies the actual Android routes. Preserve the address/prefix and
        // validate the optional peer instead of rejecting normal subnet or
        // net30 profiles.
        if (!gateway.empty()) {
            in_addr peer4{};
            in6_addr peer6{};
            const int peer_family = ipv6
                    ? inet_pton(AF_INET6, gateway.c_str(), &peer6)
                    : inet_pton(AF_INET, gateway.c_str(), &peer4);
            if (peer_family != 1) return false;
        }
        if (net30 && (ipv6 || prefix != 30 || gateway.empty())) {
            unsupported_option_seen = true;
            return false;
        }
        addresses.push_back({address, prefix, ipv6, gateway, net30});
        return true;
    }

    bool tun_builder_set_route_metric_default(int metric) override {
        if (!builder_active) return false;
        // Android VpnService.Builder has no route-metric equivalent. A
        // non-default metric must be rejected rather than silently ignored.
        // Upstream defines every negative value as "metric unspecified".
        // Android cannot represent an explicit non-negative route metric.
        if (metric >= 0) {
            unsupported_option_seen = true;
            return false;
        }
        default_route_metric = -1;
        return true;
    }

    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int) override {
        if (!builder_active || (!ipv4 && !ipv6) || routes.size() + (ipv4 ? 1 : 0) + (ipv6 ? 1 : 0) > 512) return false;
        if (ipv4) routes.push_back({"0.0.0.0", 0, default_route_metric, false, false});
        if (ipv6) routes.push_back({"::", 0, default_route_metric, true, false});
        return true;
    }

    bool tun_builder_add_route(
            const std::string &address, int prefix, int metric, bool ipv6) override {
        const int max_prefix = ipv6 ? 128 : 32;
        if (!builder_active || address.empty() || address.size() > 64 ||
            routes.size() >= 512 || prefix < 0 || prefix > max_prefix) {
            return false;
        }
        // Route metrics are not representable by Android's Builder API.
        if (metric >= 0) {
            unsupported_option_seen = true;
            return false;
        }
        routes.push_back({address, prefix, -1, ipv6, false});
        return true;
    }

    bool tun_builder_exclude_route(
            const std::string &address, int prefix, int metric, bool ipv6) override {
        const int max_prefix = ipv6 ? 128 : 32;
        if (!builder_active || address.empty() || address.size() > 64 ||
            routes.size() >= 512 || prefix < 0 || prefix > max_prefix) {
            return false;
        }
        // Excluded-route metrics cannot be represented by Android either.
        if (metric >= 0) {
            unsupported_option_seen = true;
            return false;
        }
        routes.push_back({address, prefix, -1, ipv6, true});
        return true;
    }

    bool tun_builder_set_mtu(int value) override {
        if (!builder_active || value < 576 || value > 65535) return false;
        mtu = value;
        return true;
    }

    bool tun_builder_set_session_name(const std::string &name) override {
        if (!builder_active || name.empty()) return false;
        session_name = name.substr(0, 128);
        return true;
    }

    // Android VpnService.Builder supports numeric plain DNS server addresses,
    // but not per-server ports, DoH/DoT, DNSSEC policy, SNI, or split/search
    // domains. Accept only the subset that can be represented faithfully.
    bool tun_builder_set_dns_options(
            const openvpn::DnsOptions &dns) override {
        if (!builder_active) return false;
        std::vector<std::string> parsed;
        if (dns.search_domains.size() > 16) {
            unsupported_option_seen = true;
            return false;
        }
        search_domains.clear();
        for (const auto &domain : dns.search_domains) {
            if (domain.domain.empty() || domain.domain.size() > 253) {
                unsupported_option_seen = true;
                return false;
            }
            search_domains.push_back(domain.domain);
        }
        for (const auto &entry : dns.servers) {
            const auto &server = entry.second;
            if (!server.domains.empty() || !server.sni.empty() ||
                (server.transport != openvpn::DnsServer::Transport::Unset &&
                 server.transport != openvpn::DnsServer::Transport::Plain) ||
                (server.dnssec != openvpn::DnsServer::Security::Unset &&
                 server.dnssec != openvpn::DnsServer::Security::No)) {
                unsupported_option_seen = true;
                return false;
            }
            for (const auto &address : server.addresses) {
                if (address.address.empty() || address.address.size() > 64 || address.port != 0 ||
                    parsed.size() >= 8) {
                    unsupported_option_seen = true;
                    return false;
                }
                parsed.push_back(address.address);
            }
        }
        dns_servers = std::move(parsed);
        return true;
    }

    // Android's Builder cannot faithfully implement a request to deny a
    // specific address family through this callback. Fail closed on deny.
    bool tun_builder_set_allow_family(int, bool allow) override {
        if (!allow) unsupported_option_seen = true;
        return allow;
    }

    int tun_builder_establish() override {
        if (!builder_active) return -1;
        if (unsupported_option_seen) {
            reportDiagnostic("TUN_BUILDER_REJECTED",
                    "The profile requested a tunnel option this Android adapter cannot represent safely.",
                    true, true);
            return -1;
        }
        reportDiagnostic("TUN_BUILDER_ESTABLISH_BEGIN",
                "Applying tunnel addresses, DNS and routes to Android VpnService.", false, false);
        ScopedJniEnv scoped;
        JNIEnv *env = scoped.get();
        if (!env) return -1;
        jmethodID establish_method = nullptr;
        jobject service = acquire_service(env, &establish_method, nullptr);
        if (!service || !establish_method) {
            if (service) env->DeleteLocalRef(service);
            reportDiagnostic("TUN_BUILDER_SERVICE_UNAVAILABLE",
                    "Android VpnService callback is not bound.", true, true);
            return -1;
        }

        std::vector<std::string> address_values, route_values, excluded_values;
        std::vector<jint> address_prefixes, route_prefixes, excluded_prefixes;
        for (const auto &item : addresses) {
            address_values.push_back(item.value);
            address_prefixes.push_back(static_cast<jint>(item.prefix));
        }
        for (const auto &item : routes) {
            if (item.excluded) {
                excluded_values.push_back(item.value);
                excluded_prefixes.push_back(static_cast<jint>(item.prefix));
            } else {
                route_values.push_back(item.value);
                route_prefixes.push_back(static_cast<jint>(item.prefix));
            }
        }

        jstring jname = env->NewStringUTF(session_name.c_str());
        jobjectArray jaddresses = make_string_array(env, address_values);
        jintArray jaddress_prefixes = make_int_array(env, address_prefixes);
        jobjectArray jdns = make_string_array(env, dns_servers);
        jobjectArray jsearch_domains = make_string_array(env, search_domains);
        jobjectArray jroutes = make_string_array(env, route_values);
        jintArray jroute_prefixes = make_int_array(env, route_prefixes);
        jobjectArray jexcluded = make_string_array(env, excluded_values);
        jintArray jexcluded_prefixes = make_int_array(env, excluded_prefixes);
        if (!jname || !jaddresses || !jaddress_prefixes || !jdns || !jsearch_domains || !jroutes ||
            !jroute_prefixes || !jexcluded || !jexcluded_prefixes) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (jname) env->DeleteLocalRef(jname);
            if (jaddresses) env->DeleteLocalRef(jaddresses);
            if (jaddress_prefixes) env->DeleteLocalRef(jaddress_prefixes);
            if (jdns) env->DeleteLocalRef(jdns);
            if (jsearch_domains) env->DeleteLocalRef(jsearch_domains);
            if (jroutes) env->DeleteLocalRef(jroutes);
            if (jroute_prefixes) env->DeleteLocalRef(jroute_prefixes);
            if (jexcluded) env->DeleteLocalRef(jexcluded);
            if (jexcluded_prefixes) env->DeleteLocalRef(jexcluded_prefixes);
            env->DeleteLocalRef(service);
            return -1;
        }

        jint fd = env->CallIntMethod(service, establish_method, jname, jaddresses,
                jaddress_prefixes, jdns, jroutes, jroute_prefixes, jexcluded,
                jexcluded_prefixes, jsearch_domains, static_cast<jint>(mtu));
        const bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear();

        env->DeleteLocalRef(service);
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jaddresses);
        env->DeleteLocalRef(jaddress_prefixes);
        env->DeleteLocalRef(jdns);
        env->DeleteLocalRef(jroutes);
        env->DeleteLocalRef(jroute_prefixes);
        env->DeleteLocalRef(jexcluded);
        env->DeleteLocalRef(jexcluded_prefixes);
        env->DeleteLocalRef(jsearch_domains);
        if (failed || fd < 0) {
            reportDiagnostic("TUN_BUILDER_ESTABLISH_FAILED",
                    failed ? "Android VpnService threw while establishing the TUN interface."
                           : "Android VpnService could not establish the TUN interface.",
                    true, true);
            return -1;
        }
        reportDiagnostic("TUN_BUILDER_ESTABLISHED",
                "Android TUN established; IPv4/IPv6 addresses=" +
                std::to_string(address_values.size()) + ", DNS=" +
                std::to_string(dns_servers.size()) + ", routes=" +
                std::to_string(route_values.size()) + ", excluded routes=" +
                std::to_string(excluded_values.size()) + ".",
                false, false);
        return static_cast<int>(fd);
    }

    void tun_builder_teardown(bool) override {
        reportDiagnostic("TUN_BUILDER_TEARDOWN",
                "OpenVPN is tearing down the Android TUN interface.", false, false);
        builder_active = false;
        addresses.clear();
        routes.clear();
    }

    bool socket_protect(openvpn_io::detail::socket_type socket,
                        std::string, bool) override {
        ScopedJniEnv scoped;
        JNIEnv *env = scoped.get();
        if (!env) return false;
        jmethodID protect_method = nullptr;
        jobject service = acquire_service(env, nullptr, &protect_method);
        if (!service || !protect_method) {
            if (service) env->DeleteLocalRef(service);
            return false;
        }
        const jboolean protected_socket = env->CallBooleanMethod(
                service, protect_method, static_cast<jint>(socket));
        const bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear();
        env->DeleteLocalRef(service);
        const bool ok = !failed && protected_socket == JNI_TRUE;
        // Record the result without exposing the descriptor or endpoint.
        reportDiagnostic("TRANSPORT_SOCKET_PROTECT",
                ok ? "Android VpnService.protect() succeeded."
                   : "Android VpnService.protect() failed or raised an exception.",
                !ok, !ok);
        return ok;
    }

    bool pause_on_connection_timeout() override {
        return false;
    }

    void reportDiagnostic(const std::string &name, const std::string &info,
                          bool error, bool fatal) {
        ScopedJniEnv scoped;
        JNIEnv *env = scoped.get();
        if (!env) return;
        jmethodID callback = nullptr;
        jobject service = acquire_service(env, nullptr, nullptr);
        {
            std::lock_guard<std::mutex> lock(g_service_mutex);
            callback = g_native_event;
        }
        if (!service || !callback) {
            if (service) env->DeleteLocalRef(service);
            return;
        }
        jstring jname = env->NewStringUTF(name.c_str());
        jstring jinfo = env->NewStringUTF(info.c_str());
        if (jname && jinfo) {
            env->CallVoidMethod(service, callback, jname, jinfo,
                    error ? JNI_TRUE : JNI_FALSE,
                    fatal ? JNI_TRUE : JNI_FALSE);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jname) env->DeleteLocalRef(jname);
        if (jinfo) env->DeleteLocalRef(jinfo);
        env->DeleteLocalRef(service);
    }

    void event(const openvpn::ClientAPI::Event &event) override {
        reportDiagnostic(event.name, event.info, event.error, event.fatal);
    }

    void acc_event(
            const openvpn::ClientAPI::AppCustomControlMessageEvent &) override {
        // Custom-control messages are not supported by the first profile subset.
    }

    void log(const openvpn::ClientAPI::LogInfo &entry) override {
        // Forward only transport/handshake diagnostics. Never forward raw
        // credentials, profile text, certificates, or private-key material.
        std::string line = entry.text;
        if (line.empty()) return;
        std::string lower = line;
        std::transform(lower.begin(), lower.end(), lower.begin(),
                [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        const bool useful =
                lower.find("error") != std::string::npos ||
                lower.find("warning") != std::string::npos ||
                lower.find("tls") != std::string::npos ||
                lower.find("resolve") != std::string::npos ||
                lower.find("tcp") != std::string::npos ||
                lower.find("udp") != std::string::npos ||
                lower.find("timeout") != std::string::npos ||
                lower.find("connection") != std::string::npos ||
                lower.find("peer info") != std::string::npos ||
                lower.find("control channel") != std::string::npos ||
                lower.find("push_reply") != std::string::npos ||
                lower.find("push reply") != std::string::npos ||
                lower.find("auth") != std::string::npos ||
                lower.find("verify") != std::string::npos ||
                lower.find("certificate") != std::string::npos ||
                lower.find("ifconfig") != std::string::npos ||
                lower.find("tun builder") != std::string::npos ||
                lower.find("route") != std::string::npos ||
                lower.find("options error") != std::string::npos ||
                lower.find("incoming packet") != std::string::npos ||
                lower.find("handshake") != std::string::npos ||
                lower.find("control channel") != std::string::npos ||
                lower.find("peer connection") != std::string::npos ||
                lower.find("peer-id") != std::string::npos ||
                lower.find("tls error") != std::string::npos ||
                lower.find("tls handshake") != std::string::npos ||
                lower.find("tls key negotiation") != std::string::npos ||
                lower.find("ping-restart") != std::string::npos ||
                lower.find("inactivity timeout") != std::string::npos ||
                lower.find("server poll") != std::string::npos;
        const bool sensitive =
                lower.find("password") != std::string::npos ||
                lower.find("username") != std::string::npos ||
                lower.find("auth-token") != std::string::npos ||
                lower.find("private key") != std::string::npos ||
                lower.find("-----begin") != std::string::npos ||
                lower.find("access token") != std::string::npos ||
                lower.find("secret") != std::string::npos;
        if (!useful || sensitive) return;
        if (line.size() > 600) line.resize(600);
        reportDiagnostic("CORE_LOG", line, false, false);
    }

    void external_pki_cert_request(
            openvpn::ClientAPI::ExternalPKICertRequest &) override {
        // External PKI is not supported by the first profile subset.
    }

    void external_pki_sign_request(
            openvpn::ClientAPI::ExternalPKISignRequest &) override {
        // External PKI is not supported by the first profile subset.
    }
};

static_assert(!std::is_abstract<RahYabOpenVPNClient>::value,
              "RahYabVPN must implement every pure OpenVPNClient callback");

// One native client and one joinable worker per VPN session. The owner keeps
// the client alive until stop has joined the worker; no detached threads.
struct NativeSession {
    std::shared_ptr<RahYabOpenVPNClient> client;
    std::thread worker;
    std::atomic<bool> finished{false};
};
std::mutex g_session_mutex;
std::shared_ptr<NativeSession> g_session;
bool g_session_stopping = false;


jstring to_java_string(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeCryptoSelfTest(
        JNIEnv *env, jclass) {
    try {
        openvpn::ClientAPI::OpenVPNClientHelper helper;
        const std::string platform = openvpn::ClientAPI::OpenVPNClientHelper::platform();
        const std::string crypto = helper.crypto_self_test();

        std::string report = "OpenVPN 3 core helper: OK\n";
        report += "Platform: " + platform + "\n";
        if (!crypto.empty()) {
            report += "OpenVPN helper crypto self-test output:\n" + crypto;
        } else {
            // Some OpenVPN 3 builds expose crypto_self_test() but return no
            // diagnostics. Fall back to a SHA-256 known-answer test through
            // the same OpenSSL crypto library linked into this JNI module.
            static const unsigned char expected[] = {
                0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
                0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
                0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c,
                0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad
            };
            unsigned char digest[EVP_MAX_MD_SIZE] = {};
            unsigned int digest_length = 0;
            const int ok = EVP_Digest("abc", 3, digest, &digest_length,
                                      EVP_sha256(), nullptr);
            const bool matches = ok == 1 && digest_length == sizeof(expected) &&
                    std::equal(std::begin(expected), std::end(expected), digest);
            report += matches
                    ? "SHA-256 known-answer test: PASS (OpenSSL EVP; abc)"
                    : "SHA-256 known-answer test: FAIL (OpenSSL EVP)";
        }
        return to_java_string(env, report);
    } catch (const std::exception &e) {
        return to_java_string(env, std::string("OpenVPN core self-test exception: ") + e.what());
    } catch (...) {
        return to_java_string(env, "OpenVPN core self-test failed with an unknown native exception");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeValidateProfile(
        JNIEnv *env, jclass, jstring profile) {
    if (!profile) {
        return to_java_string(env, "Profile is empty.");
    }
    const char *chars = env->GetStringUTFChars(profile, nullptr);
    if (!chars) {
        return to_java_string(env, "Unable to read profile text.");
    }
    const std::string content(chars);
    env->ReleaseStringUTFChars(profile, chars);

    if (content.empty()) {
        return to_java_string(env, "Profile is empty.");
    }
    if (content.size() > static_cast<size_t>(
            openvpn::ClientAPI::OpenVPNClientHelper::max_profile_size())) {
        return to_java_string(env, "Profile exceeds the OpenVPN core size limit.");
    }

    try {
        openvpn::ClientAPI::OpenVPNClientHelper helper;
        openvpn::ClientAPI::Config config;
        config.content = content;
        const openvpn::ClientAPI::EvalConfig result = helper.eval_config(config);
        if (result.error) {
            return to_java_string(env, std::string("Profile rejected: ") + result.message);
        }

        const bool supported_auth_subset =
                result.autologin &&
                !result.externalPki &&
                !result.privateKeyPasswordRequired &&
                result.staticChallenge.empty();

        std::string report = "Profile syntax: accepted by OpenVPN 3 parser.\n";
        report += std::string("Initial connection subset: ") +
                (supported_auth_subset
                        ? "authentication mode is within the initial autologin-only subset; full connection is not enabled yet"
                        : "not supported by the initial autologin-only subset") +
                "\n";
        report += std::string("Authentication: ") +
                (result.autologin ? "autologin" : "username/password required") +
                "\n";
        report += std::string("External PKI: ") +
                (result.externalPki ? "required; unsupported" : "not required") +
                "\n";
        report += std::string("Static challenge: ") +
                (result.staticChallenge.empty() ? "not reported" : "present; unsupported") +
                "\n";
        report += std::string("Private-key password: ") +
                (result.privateKeyPasswordRequired ? "required; unsupported" : "not reported as required");
        if (!result.remoteHost.empty()) {
            report += "\nRemote host: " + result.remoteHost;
        }
        if (!result.remotePort.empty()) {
            report += "\nRemote port: " + result.remotePort;
        }
        return to_java_string(env, report);
    } catch (const std::exception &e) {
        return to_java_string(env, std::string("Profile validation exception: ") + e.what());
    } catch (...) {
        return to_java_string(env, "Profile validation failed with an unknown native exception.");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeStartSession(
        JNIEnv *env, jclass, jstring profile) {
    if (!profile) return to_java_string(env, "فایل تنظیمات خالی است.");
    const char *chars = env->GetStringUTFChars(profile, nullptr);
    if (!chars) return to_java_string(env, "خواندن فایل تنظیمات ناموفق بود.");
    const std::string content(chars);
    env->ReleaseStringUTFChars(profile, chars);
    if (content.empty() || content.size() > static_cast<size_t>(
            openvpn::ClientAPI::OpenVPNClientHelper::max_profile_size())) {
        return to_java_string(env, "حجم یا محتوای فایل تنظیمات معتبر نیست.");
    }

    // Reap a naturally completed worker before allowing another connection.
    // A finished std::thread remains joinable until joined; replacing its owner
    // without joining would call std::terminate from the thread destructor.
    std::shared_ptr<NativeSession> completed_session;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        if (g_session && g_session->finished.load(std::memory_order_acquire)) {
            completed_session = g_session;
            g_session.reset();
        } else if (g_session || g_session_stopping) {
            return to_java_string(env, "یک نشست VPN در حال اجرا یا توقف است.");
        }
    }
    if (completed_session && completed_session->worker.joinable()) {
        completed_session->worker.join();
    }

    try {
        auto session = std::make_shared<NativeSession>();
        session->client = std::make_shared<RahYabOpenVPNClient>();
        openvpn::ClientAPI::Config config;
        config.content = content;
        const auto evaluated = session->client->eval_config(config);
        if (evaluated.error)
            return to_java_string(env, std::string("پروفایل رد شد: ") + evaluated.message);
        if (!evaluated.autologin || evaluated.externalPki ||
            evaluated.privateKeyPasswordRequired || !evaluated.staticChallenge.empty()) {
            return to_java_string(env,
                    "این نسخه فقط پروفایل‌های ورود خودکار بدون چالش و کلید رمزدار را می‌پذیرد.");
        }

        {
            std::lock_guard<std::mutex> lock(g_session_mutex);
            if (g_session || g_session_stopping)
                return to_java_string(env, "یک نشست VPN هم‌زمان شروع شده است.");
            g_session = session;
        }
        try {
            session->worker = std::thread([session]() {
                const auto client = session->client;
                try {
                    client->reportDiagnostic("NATIVE_CONNECT_BEGIN",
                            "OpenVPN 3 connect() worker entered.", false, false);
                    const auto result = client->connect();
                    std::string detail = result.message;
                    if (detail.empty()) {
                        detail = result.error
                                ? "OpenVPN connect() returned an error without a message."
                                : "OpenVPN connect() returned without an error message.";
                    }
                    if (detail.size() > 1200) detail.resize(1200);
                    client->reportDiagnostic("NATIVE_CONNECT_RESULT", detail,
                            result.error, false);
                } catch (const std::exception &e) {
                    std::string detail = e.what() ? e.what() : "std::exception without message";
                    if (detail.size() > 1200) detail.resize(1200);
                    client->reportDiagnostic("NATIVE_CONNECT_EXCEPTION", detail, true, true);
                } catch (...) {
                    client->reportDiagnostic("NATIVE_CONNECT_EXCEPTION",
                            "Unknown C++ exception escaped OpenVPN connect().", true, true);
                }
                session->finished.store(true, std::memory_order_release);
            });
        } catch (...) {
            std::lock_guard<std::mutex> lock(g_session_mutex);
            if (g_session == session) g_session.reset();
            throw;
        }
        return to_java_string(env, "درخواست اتصال به هسته OpenVPN 3 تحویل شد؛ وضعیت نهایی از رویدادهای موتور مشخص می‌شود.");
    } catch (const std::exception &e) {
        return to_java_string(env, std::string("شروع نشست ناموفق بود: ") + e.what());
    } catch (...) {
        return to_java_string(env, "شروع نشست با خطای ناشناخته روبه‌رو شد.");
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeTunnelBytes(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_session_mutex);
    if (!g_session || g_session_stopping || g_session->finished.load()) return nullptr;
    const auto stats = g_session->client->tun_stats();
    const jlong values[] = {stats.bytesIn, stats.bytesOut};
    jlongArray snapshot = env->NewLongArray(2);
    if (snapshot) env->SetLongArrayRegion(snapshot, 0, 2, values);
    return snapshot;
}

extern "C" JNIEXPORT void JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeStopSession(
        JNIEnv *, jclass) {
    const auto stop_started = std::chrono::steady_clock::now();
    std::shared_ptr<NativeSession> session;
    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        if (!g_session) {
            // Never leave the process-wide state stuck in "stopping" when
            // cleanup is requested after a session was already reaped.
            g_session_stopping = false;
            return;
        }
        g_session_stopping = true;
        session = g_session;
    }

    // Emit phase markers before potentially blocking OpenVPN shutdown calls.
    // These let the Android diagnostic report distinguish stop() stalls from
    // worker-join stalls without detaching or freeing a live worker.
    session->client->reportDiagnostic("NATIVE_STOP_BEGIN",
            "Native stop requested; worker_finished="
                    + std::string(session->finished.load(std::memory_order_acquire)
                            ? "true" : "false")
                    + "; worker_joinable="
                    + std::string(session->worker.joinable() ? "true" : "false"),
            false, false);

    const auto client_stop_started = std::chrono::steady_clock::now();
    session->client->stop();
    const auto client_stop_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - client_stop_started).count();
    session->client->reportDiagnostic("NATIVE_STOP_CLIENT_RETURNED",
            "OpenVPN client stop() returned after "
                    + std::to_string(client_stop_ms) + " ms.",
            false, false);

    if (session->worker.joinable() &&
        session->worker.get_id() != std::this_thread::get_id()) {
        session->client->reportDiagnostic("NATIVE_STOP_JOIN_BEGIN",
                "Waiting for the OpenVPN connect worker to finish; worker_finished="
                        + std::string(session->finished.load(std::memory_order_acquire)
                                ? "true" : "false"),
                false, false);
        const auto join_started = std::chrono::steady_clock::now();
        session->worker.join();
        const auto join_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - join_started).count();
        session->client->reportDiagnostic("NATIVE_STOP_JOIN_RETURNED",
                "OpenVPN worker join returned after "
                        + std::to_string(join_ms) + " ms.",
                false, false);
    }

    const bool worker_finished =
            session->finished.load(std::memory_order_acquire);
    const auto total_stop_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - stop_started).count();
    session->client->reportDiagnostic("NATIVE_STOP_COMPLETE",
            "Native stop completed after " + std::to_string(total_stop_ms)
                    + " ms; worker_finished="
                    + std::string(worker_finished ? "true" : "false"),
            false, false);

    {
        std::lock_guard<std::mutex> lock(g_session_mutex);
        if (g_session == session) g_session.reset();
        g_session_stopping = false;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeBindTunService(
        JNIEnv *env, jclass, jobject service) {
    if (!service) return to_java_string(env, "SERVICE_NULL");
    if (!g_vm) return to_java_string(env, "JAVA_VM_NOT_INITIALIZED");
    jclass cls = env->GetObjectClass(service);
    if (!cls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return to_java_string(env, "GET_SERVICE_CLASS_FAILED");
    }
    jmethodID establish = env->GetMethodID(cls, "establishTun",
            "(Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;[I[Ljava/lang/String;I)I");
    jmethodID protect = env->GetMethodID(cls, "protectTransportSocket", "(I)Z");
    jmethodID event_callback = env->GetMethodID(cls, "onNativeEvent",
            "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
    env->DeleteLocalRef(cls);
    if (!establish || !protect || !event_callback || env->ExceptionCheck()) {
        std::string detail = "JNI_METHOD_LOOKUP_FAILED:";
        if (!establish) detail += " establishTun";
        if (!protect) detail += " protectTransportSocket";
        if (!event_callback) detail += " onNativeEvent";
        if (env->ExceptionCheck()) env->ExceptionClear();
        return to_java_string(env, detail);
    }
    jobject global_service = env->NewGlobalRef(service);
    if (!global_service) return to_java_string(env, "NEW_GLOBAL_REF_FAILED");
    {
        std::lock_guard<std::mutex> lock(g_service_mutex);
        if (g_vpn_service) env->DeleteGlobalRef(g_vpn_service);
        g_vpn_service = global_service;
        g_establish_tun = establish;
        g_protect_socket = protect;
        g_native_event = event_callback;
    }
    return to_java_string(env, "OK");
}

extern "C" JNIEXPORT void JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeUnbindTunService(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_service_mutex);
    if (g_vpn_service) env->DeleteGlobalRef(g_vpn_service);
    g_vpn_service = nullptr;
    g_establish_tun = nullptr;
    g_protect_socket = nullptr;
    g_native_event = nullptr;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
