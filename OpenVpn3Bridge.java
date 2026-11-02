package ir.emergencyconnect.engine;

/**
 * Availability gate for the embedded OpenVPN 3 engine.
 *
 * Loading libopenvpn3_jni.so only proves that the shared library can be
 * loaded. It does not prove that a session can be created, that Android's
 * VpnService TUN is connected to the core, or that transport sockets are
 * protected. Keep the engine unavailable until the complete JNI session
 * lifecycle and TunBuilder adapter are implemented and tested.
 */
public final class OpenVpn3Bridge {
    private static final boolean LIBRARY_LOADED;

    // Deliberately false: the current native library exports JNI_OnLoad only.
    // Change this only in the same change that adds and verifies the complete
    // session API, TUN callbacks, socket protection, and lifecycle handling.
    private static final boolean SESSION_API_READY = false;

    static {
        boolean loaded;
        try {
            System.loadLibrary("openvpn3_jni");
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private OpenVpn3Bridge() {}

    /** True only when a usable embedded session engine is available. */
    public static boolean isAvailable() {
        return LIBRARY_LOADED && SESSION_API_READY;
    }

    /** Diagnostic distinction between a packaged library and a usable engine. */
    public static String availabilityMessage() {
        if (!LIBRARY_LOADED) {
            return "کتابخانه بومی OpenVPN 3 در این نسخه بارگذاری نشده است.";
        }
        return "کتابخانه بومی بارگذاری شده، اما رابط نشست و تونل اندروید هنوز کامل و آزمایش نشده است.";
    }
}
