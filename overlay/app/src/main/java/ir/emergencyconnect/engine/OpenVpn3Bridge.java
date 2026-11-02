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
