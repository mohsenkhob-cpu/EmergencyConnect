package hev.htproxy;

/**
 * JNI namespace required by the upstream MIT-licensed hev-socks5-tunnel
 * (https://github.com/heiher/hev-socks5-tunnel).
 * Compiled with the upstream default PKGNAME=hev/htproxy and
 * CLSNAME=TProxyService; do not rename this class.
 */
public final class TProxyService {
    private TProxyService() {}
    static { System.loadLibrary("hev-socks5-tunnel"); }
    public static native boolean TProxyStartService(String configPath, int tunFd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
