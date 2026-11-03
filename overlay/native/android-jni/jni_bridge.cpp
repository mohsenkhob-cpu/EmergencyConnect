#include <jni.h>

#include <exception>
#include <mutex>
#include <string>
#include <type_traits>
#include <vector>

#include "ovpncli.hpp"

// Compile-time contract check for the pinned OpenVPN 3 client API.
// The class remains intentionally unavailable as a session API until the
// Android TunBuilder callbacks and managed worker lifecycle are implemented.
namespace {

JavaVM *g_vm = nullptr;
jobject g_vpn_service = nullptr;
jmethodID g_establish_tun = nullptr;
jmethodID g_protect_socket = nullptr;
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

    // Per-client callback state. This is deliberately native-only for now;
    // no TUN descriptor is established until the Java service callback exists.
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
        unsupported_option_seen = false;
        builder_active = true;
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
        // Android Builder.addAddress() cannot express OpenVPN's gateway
        // and net30 point-to-point semantics. Do not silently drop them.
        if (!gateway.empty() || net30) {
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
        if (metric != -1) {
            unsupported_option_seen = true;
            return false;
        }
        default_route_metric = metric;
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
        if (metric != -1) {
            unsupported_option_seen = true;
            return false;
        }
        routes.push_back({address, prefix, metric, ipv6, false});
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
        if (metric != -1) {
            unsupported_option_seen = true;
            return false;
        }
        routes.push_back({address, prefix, metric, ipv6, true});
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
        if (!dns.search_domains.empty()) {
            unsupported_option_seen = true;
            return false;
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
        if (!builder_active || unsupported_option_seen) return -1;
        ScopedJniEnv scoped;
        JNIEnv *env = scoped.get();
        if (!env) return -1;
        jmethodID establish_method = nullptr;
        jobject service = acquire_service(env, &establish_method, nullptr);
        if (!service || !establish_method) return -1;

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
        jobjectArray jroutes = make_string_array(env, route_values);
        jintArray jroute_prefixes = make_int_array(env, route_prefixes);
        jobjectArray jexcluded = make_string_array(env, excluded_values);
        jintArray jexcluded_prefixes = make_int_array(env, excluded_prefixes);
        if (!jname || !jaddresses || !jaddress_prefixes || !jdns || !jroutes ||
            !jroute_prefixes || !jexcluded || !jexcluded_prefixes) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (jname) env->DeleteLocalRef(jname);
            if (jaddresses) env->DeleteLocalRef(jaddresses);
            if (jaddress_prefixes) env->DeleteLocalRef(jaddress_prefixes);
            if (jdns) env->DeleteLocalRef(jdns);
            if (jroutes) env->DeleteLocalRef(jroutes);
            if (jroute_prefixes) env->DeleteLocalRef(jroute_prefixes);
            if (jexcluded) env->DeleteLocalRef(jexcluded);
            if (jexcluded_prefixes) env->DeleteLocalRef(jexcluded_prefixes);
            env->DeleteLocalRef(service);
            return -1;
        }

        jint fd = env->CallIntMethod(service, establish_method, jname, jaddresses,
                jaddress_prefixes, jdns, jroutes, jroute_prefixes, jexcluded,
                jexcluded_prefixes, static_cast<jint>(mtu));
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
        return failed ? -1 : static_cast<int>(fd);
    }

    void tun_builder_teardown(bool) override {
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
        if (!service || !protect_method) return false;
        const jboolean protected_socket = env->CallBooleanMethod(
                service, protect_method, static_cast<jint>(socket));
        const bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear();
        env->DeleteLocalRef(service);
        return !failed && protected_socket == JNI_TRUE;
    }

    bool pause_on_connection_timeout() override {
        return false;
    }

    void event(const openvpn::ClientAPI::Event &) override {
        // Session event forwarding is added with the managed JNI lifecycle.
    }

    void acc_event(
            const openvpn::ClientAPI::AppCustomControlMessageEvent &) override {
        // Custom-control messages are not supported by the first profile subset.
    }

    void log(const openvpn::ClientAPI::LogInfo &) override {
        // Log forwarding is added with the managed JNI lifecycle.
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
        if (crypto.empty()) {
            report += "Crypto self-test: no output from this build; cryptographic self-test is not verified.";
        } else {
            report += "Crypto self-test output:\n" + crypto;
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

extern "C" JNIEXPORT jboolean JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeBindTunService(
        JNIEnv *env, jclass, jobject service) {
    if (!service || !g_vm) return JNI_FALSE;
    jclass cls = env->GetObjectClass(service);
    if (!cls) return JNI_FALSE;
    jmethodID establish = env->GetMethodID(cls, "establishTun",
            "(Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;[II)I");
    jmethodID protect = env->GetMethodID(cls, "protectTransportSocket", "(I)Z");
    env->DeleteLocalRef(cls);
    if (!establish || !protect || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
    jobject global_service = env->NewGlobalRef(service);
    if (!global_service) return JNI_FALSE;
    {
        std::lock_guard<std::mutex> lock(g_service_mutex);
        if (g_vpn_service) env->DeleteGlobalRef(g_vpn_service);
        g_vpn_service = global_service;
        g_establish_tun = establish;
        g_protect_socket = protect;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_ir_emergencyconnect_engine_OpenVpn3Bridge_nativeUnbindTunService(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_service_mutex);
    if (g_vpn_service) env->DeleteGlobalRef(g_vpn_service);
    g_vpn_service = nullptr;
    g_establish_tun = nullptr;
    g_protect_socket = nullptr;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
