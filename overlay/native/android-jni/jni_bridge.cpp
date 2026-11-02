#include <jni.h>
#include <string>

// This library is deliberately a compile-stage integration target.
// Session creation is not exposed until the OpenVPNClient/TunBuilder JNI
// adapter and Android service lifecycle are implemented and tested.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *)
{
    return JNI_VERSION_1_6;
}
