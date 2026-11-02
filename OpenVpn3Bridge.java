package ir.emergencyconnect.engine;

/**
 * JNI boundary reserved for OpenVPN 3 Core.
 * This class deliberately reports unavailable until libopenvpn3.so is built
 * and packaged for the Android ABIs. It never reports a false connected state.
 */
public final class OpenVpn3Bridge {
    private static final boolean LOADED;
    static {
        boolean loaded;
        try {
            System.loadLibrary("openvpn3_jni");
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private OpenVpn3Bridge() {}

    public static boolean isAvailable() { return LOADED; }

    public static String availabilityMessage() {
        return LOADED
                ? "موتور بومی بارگذاری شده است؛ اتصال فقط پس از تکمیل رابط TUN قابل استفاده است."
                : "موتور OpenVPN 3 هنوز در این نسخه بسته‌بندی نشده است.";
    }

    // Native methods will be implemented by the OpenVPN 3 JNI/TunBuilder layer.
    public static native long createSession(String inlineProfile);
    public static native void stopSession(long sessionHandle);
}
