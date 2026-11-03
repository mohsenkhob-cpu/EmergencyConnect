package ir.emergencyconnect.engine;

/**
 * Availability gate and diagnostics for the embedded OpenVPN 3 engine.
 *
 * A successful native self-test proves that the packaged OpenVPN core can be
 * entered through JNI. It does NOT prove that a VPN session can be connected.
 * Keep SESSION_API_READY false until session lifecycle, Android TunBuilder
 * callbacks, socket protection and device traffic tests are complete.
 */
public final class OpenVpn3Bridge {
    private static final boolean LIBRARY_LOADED;

    // Deliberately false: no complete JNI session/TUN adapter exists yet.
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

    private static native String nativeCryptoSelfTest();
    private static native String nativeValidateProfile(String profile);
    private static native String nativeStartSession(String profile);
    private static native void nativeStopSession();

    /** Start one autologin-only native OpenVPN session on its managed worker. */
    public static String startSession(String profile) {
        if (!LIBRARY_LOADED) return "کتابخانه بومی OpenVPN 3 بارگذاری نشده است.";
        if (profile == null || profile.trim().isEmpty()) return "فایل تنظیمات خالی است.";
        try {
            return nativeStartSession(profile);
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return "شروع نشست داخلی در دسترس نیست: " + e.getMessage();
        }
    }

    /** Request native disconnect and join its worker before releasing session state. */
    public static void stopSession() {
        if (!LIBRARY_LOADED) return;
        try {
            nativeStopSession();
        } catch (UnsatisfiedLinkError | SecurityException ignored) {
            // Safe when the native library could not be loaded.
        }
    }


    /**
     * Parse and inspect an OpenVPN profile with the embedded core.
     * This does not connect and does not imply that the profile's tunnel
     * options are supported by the Android adapter.
     */
    public static String validateProfile(String profile) {
        if (!LIBRARY_LOADED) {
            return "کتابخانه بومی OpenVPN 3 بارگذاری نشده است.";
        }
        if (profile == null || profile.trim().isEmpty()) {
            return "فایل تنظیمات خالی است.";
        }
        try {
            String result = nativeValidateProfile(profile);
            return result == null || result.isEmpty()
                    ? "هسته نتیجه‌ای برای بررسی فایل برنگرداند."
                    : result;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return "بررسی فایل با هسته داخلی در دسترس نیست: " + e.getMessage();
        }
    }
    private static native String nativeBindTunService(RahYabVpnService service);
    private static native void nativeUnbindTunService();

    /** Bind Android TUN and socket-protection callbacks for the service lifetime. */
    public static String bindTunService(RahYabVpnService service) {
        if (!LIBRARY_LOADED) return "LIBRARY_NOT_LOADED";
        if (service == null) return "SERVICE_NULL";
        try {
            String result = nativeBindTunService(service);
            return result == null ? "JNI_RETURNED_NULL" : result;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return "JNI_CALL_FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Release the JNI global reference held for Android service callbacks. */
    public static void unbindTunService() {
        if (!LIBRARY_LOADED) return;
        try {
            nativeUnbindTunService();
        } catch (UnsatisfiedLinkError | SecurityException ignored) {
            // Safe during service teardown if the native library was unavailable.
        }
    }

    /** True only when a usable embedded VPN session engine is available. */
    public static boolean isAvailable() {
        return LIBRARY_LOADED && SESSION_API_READY;
    }

    /**
     * Runs the OpenVPN core's own crypto self-test through the packaged JNI
     * library. This is a diagnostic only; it does not start a VPN connection.
     */
    public static String runCoreSelfTest() {
        if (!LIBRARY_LOADED) {
            return "کتابخانه بومی OpenVPN 3 بارگذاری نشده است.";
        }
        try {
            String result = nativeCryptoSelfTest();
            return result == null || result.isEmpty()
                    ? "آزمون رمزنگاری در این نسخه خروجی تولید نکرد. رابط JNI اجرا شده، اما نتیجه آزمون رمزنگاری قابل تأیید نیست؛ این وضعیت نه موفقیت آزمون است و نه شکست آن."
                    : result;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return "رابط آزمایش بومی در این نسخه در دسترس نیست: " + e.getMessage();
        }
    }

    /** Diagnostic distinction between a packaged library and a usable engine. */
    public static String availabilityMessage() {
        if (!LIBRARY_LOADED) {
            return "کتابخانه بومی OpenVPN 3 در این نسخه بارگذاری نشده است.";
        }
        return "کتابخانه و آزمون بومی در دسترس‌اند، اما رابط نشست و تونل اندروید هنوز کامل و آزمایش نشده است.";
    }
}
