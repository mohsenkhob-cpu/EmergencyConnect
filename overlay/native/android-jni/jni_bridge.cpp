#include <jni.h>

#include <exception>
#include <string>
#include <type_traits>
#include <vector>

#include "ovpncli.hpp"

// Compile-time contract check for the pinned OpenVPN 3 client API.
// The class remains intentionally unavailable as a session API until the
// Android TunBuilder callbacks and managed worker lifecycle are implemented.
namespace {

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
        unsupported_option_seen = false;
        builder_active = true;
        return true;
    }

    bool tun_builder_set_layer(int layer) override {
        return layer == 3;
    }

    bool tun_builder_set_remote_address(
            const std::string &address, bool ipv6) override {
        if (!builder_active || address.empty()) return false;
        remote_address = address;
        remote_ipv6 = ipv6;
        return true;
    }

    bool tun_builder_add_address(
            const std::string &address, int prefix, const std::string &gateway,
            bool ipv6, bool net30) override {
        const int max_prefix = ipv6 ? 128 : 32;
        if (!builder_active || address.empty() || prefix < 0 || prefix > max_prefix) {
            return false;
        }
        addresses.push_back({address, prefix, ipv6, gateway, net30});
        return true;
    }

    bool tun_builder_set_route_metric_default(int metric) override {
        if (!builder_active) return false;
        default_route_metric = metric;
        return true;
    }

    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int) override {
        if (!builder_active || (!ipv4 && !ipv6)) return false;
        if (ipv4) routes.push_back({"0.0.0.0", 0, default_route_metric, false, false});
        if (ipv6) routes.push_back({"::", 0, default_route_metric, true, false});
        return true;
    }

    bool tun_builder_add_route(
            const std::string &address, int prefix, int metric, bool ipv6) override {
        const int max_prefix = ipv6 ? 128 : 32;
        if (!builder_active || address.empty() || prefix < 0 || prefix > max_prefix) {
            return false;
        }
        routes.push_back({address, prefix, metric, ipv6, false});
        return true;
    }

    bool tun_builder_exclude_route(
            const std::string &address, int prefix, int metric, bool ipv6) override {
        const int max_prefix = ipv6 ? 128 : 32;
        if (!builder_active || address.empty() || prefix < 0 || prefix > max_prefix) {
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

    // DNS transport/options are intentionally rejected until the complete
    // plain-DNS and search-domain mapping to VpnService.Builder is implemented.
    bool tun_builder_set_dns_options(
            const openvpn::DnsOptions &) override {
        unsupported_option_seen = true;
        return false;
    }

    // Android's Builder cannot faithfully implement a request to deny a
    // specific address family through this callback. Fail closed on deny.
    bool tun_builder_set_allow_family(int, bool allow) override {
        if (!allow) unsupported_option_seen = true;
        return allow;
    }

    int tun_builder_establish() override {
        // No Java VpnService.Builder callback is wired yet. Never return a
        // fake descriptor or claim success.
        return -1;
    }

    void tun_builder_teardown(bool) override {
        builder_active = false;
        addresses.clear();
        routes.clear();
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

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}
