#include <jni.h>

#include <type_traits>

#include "ovpncli.hpp"

// Compile-time contract check for the pinned OpenVPN 3 client API.
// This class is deliberately not exposed as a session API yet: the Android
// TunBuilder/JNI callbacks and worker lifecycle must be completed first.
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

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}
