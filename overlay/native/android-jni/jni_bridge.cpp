#include <jni.h>

#include <exception>
#include <string>
#include <type_traits>

#include "ovpncli.hpp"

// Compile-time contract check for the pinned OpenVPN 3 client API.
// The class remains intentionally unavailable as a session API until the
// Android TunBuilder callbacks and managed worker lifecycle are implemented.
namespace {

class RahYabOpenVPNClient final : public openvpn::ClientAPI::OpenVPNClient {
  public:
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

        std::string report = "OpenVPN 3 core helper: OK\\n";
        report += "Platform: " + platform + "\\n";
        if (crypto.empty()) {
            report += "Crypto self-test: no output from this build; cryptographic self-test is not verified.";
        } else {
            report += "Crypto self-test output:\\n" + crypto;
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
