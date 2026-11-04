package com.rahyab.next

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet6Address
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * Minimal Android host for the official libbox engine.
 * Optional root/shell/bridge integrations are intentionally not enabled.
 */
class SingBoxVpnService : VpnService() {
    private var server: CommandServer? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var profile: String? = null
    private val channel = "rahyab-singbox"
    private val notificationId = 7301

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "وضعیت راه‌یاب", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            sendEvent(EVENT_STOPPED)
            stopSelf()
            return START_NOT_STICKY
        }
        profile = intent?.getStringExtra(EXTRA_PROFILE)
        if (profile.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(notificationId, notification("در حال راه‌اندازی موتور VPN…"))
        sendEvent(EVENT_STARTING)
        Thread {
            try {
                val setup = SetupOptions().apply {
                    basePath = filesDir.absolutePath
                    workingPath = (getExternalFilesDir(null) ?: filesDir).absolutePath
                    tempPath = cacheDir.absolutePath
                    fixAndroidStack = true
                    logMaxLines = 3000
                    debug = false
                    appVersion = "2"
                    appMarketingVersion = "0.2.0"
                }
                Libbox.setup(setup)

                val platform = Proxy.newProxyInstance(
                    PlatformInterface::class.java.classLoader,
                    arrayOf(PlatformInterface::class.java),
                    PlatformHandler()
                ) as PlatformInterface

                val callbacks = Proxy.newProxyInstance(
                    CommandServerHandler::class.java.classLoader,
                    arrayOf(CommandServerHandler::class.java),
                    CommandHandler()
                ) as CommandServerHandler

                val instance = CommandServer(callbacks, platform)
                server = instance
                instance.start()
                instance.startOrReloadService(profile!!, OverrideOptions())
                Log.i(TAG, "libbox accepted the profile and started its service")
                updateNotification("موتور VPN راه‌اندازی شد؛ وضعیت مسیر اینترنت در حال بررسی است")
                sendEvent(EVENT_ENGINE_STARTED)
            } catch (e: Exception) {
                Log.e(TAG, "sing-box startup failed", e)
                updateNotification("خطای راه‌اندازی: ${e.message ?: e.javaClass.simpleName}")
                stopEngine()
                sendEvent(EVENT_ERROR, e.message ?: e.javaClass.simpleName)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private inner class PlatformHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val a = args ?: emptyArray()
            return when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    (a.firstOrNull() as? Int)?.let { protect(it) }
                    null
                }
                "openTun" -> openTun(a[0] as TunOptions)
                "getInterfaces" -> getInterfaces()
                "startDefaultInterfaceMonitor", "closeDefaultInterfaceMonitor",
                "clearDNSCache", "startNeighborMonitor", "closeNeighborMonitor",
                "registerMyInterface" -> null
                "underNetworkExtension", "includeAllNetworks",
                "usePlatformShell", "usePlatformBridge", "usePlatformAutoRedirect" -> false
                "readWIFIState", "localDNSTransport" -> null
                "tailscaleHostname" -> "RahYab-Android"
                "toString" -> "RahYabPlatformInterface"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === a.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        }
    }

    private inner class CommandHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "serviceStop" -> {
                    stopEngine()
                    stopSelf()
                    null
                }
                "serviceReload" -> {
                    profile?.let { server?.startOrReloadService(it, OverrideOptions()) }
                    null
                }
                "getSystemProxyStatus" -> SystemProxyStatus().apply {
                    available = false
                    enabled = false
                }
                "connectSSHAgent" -> -1
                "toString" -> "RahYabCommandHandler"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        }
    }

    private fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) error("مجوز VPN صادر نشده است")
        val builder = Builder().setSession("راه‌یاب").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val address = v4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val address = v6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            val r4 = options.inet4RouteRange
            while (r4.hasNext()) {
                val route = r4.next()
                builder.addRoute(route.address(), route.prefix())
            }
            val r6 = options.inet6RouteRange
            while (r6.hasNext()) {
                val route = r6.next()
                builder.addRoute(route.address(), route.prefix())
            }
            val included = options.includePackage
            while (included.hasNext()) builder.addAllowedApplication(included.next())
            val excluded = options.excludePackage
            while (excluded.hasNext()) builder.addDisallowedApplication(excluded.next())
        }

        val descriptor = builder.establish() ?: error("Android نتوانست تونل VPN را بسازد")
        tunnel?.close()
        tunnel = descriptor
        sendEvent(EVENT_TUN_ESTABLISHED)
        return descriptor.fd
    }

    private fun getInterfaces(): NetworkInterfaceIterator {
        val values = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val enumeration = JavaNetworkInterface.getNetworkInterfaces()
        while (enumeration.hasMoreElements()) {
            val item = enumeration.nextElement()
            if (item.isLoopback) continue
            val addresses = item.interfaceAddresses.mapNotNull { entry ->
                val host = entry.address ?: return@mapNotNull null
                val ip = if (host is Inet6Address) host.hostAddress.substringBefore('%') else host.hostAddress
                "$ip/${entry.networkPrefixLength}"
            }
            val value = io.nekohasekai.libbox.NetworkInterface().apply {
                name = item.name
                index = item.index
                mtu = runCatching { item.mtu }.getOrDefault(1500)
                type = Libbox.InterfaceTypeOther
                this.addresses = iterator(addresses, StringIterator::class.java)
                dnsServer = iterator(emptyList(), StringIterator::class.java)
                gateway = iterator(emptyList(), StringIterator::class.java)
                flags = 0
                metered = false
            }
            values.add(value)
        }
        return iterator(values, NetworkInterfaceIterator::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> iterator(values: List<Any>, type: Class<*>): T {
        var position = 0
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hasNext" -> position < values.size
                "next" -> values[position++]
                "len" -> values.size
                "toString" -> "RahYabIterator"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        } as T
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private fun stopEngine() {
        runCatching { server?.closeService() }.onFailure { Log.w(TAG, "closeService", it) }
        runCatching { server?.close() }.onFailure { Log.w(TAG, "close", it) }
        server = null
        runCatching { tunnel?.close() }
        tunnel = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun sendEvent(event: String, detail: String? = null) {
        val intent = Intent(ACTION_EVENT).setPackage(packageName)
            .putExtra(EXTRA_EVENT, event)
        if (detail != null) intent.putExtra(EXTRA_DETAIL, detail)
        sendBroadcast(intent)
    }

    private fun notification(message: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("راه‌یاب VPN")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
    override fun onRevoke() {
        stopEngine()
        stopSelf()
        super.onRevoke()
    }
    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RahYabSingBox"
        private const val EXTRA_PROFILE = "rahyab.profile"
        private const val ACTION_STOP = "com.rahyab.next.STOP_SINGBOX"
        const val ACTION_EVENT = "com.rahyab.next.SINGBOX_EVENT"
        const val EXTRA_EVENT = "rahyab.event"
        const val EXTRA_DETAIL = "rahyab.detail"
        private const val EVENT_STARTING = "STARTING"
        private const val EVENT_ENGINE_STARTED = "ENGINE_STARTED"
        private const val EVENT_TUN_ESTABLISHED = "TUN_ESTABLISHED"
        private const val EVENT_STOPPED = "STOPPED"
        private const val EVENT_ERROR = "ERROR"

        @JvmStatic
        fun start(context: Context, profile: String) {
            val intent = Intent(context, SingBoxVpnService::class.java).putExtra(EXTRA_PROFILE, profile)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        @JvmStatic
        fun stop(context: Context) {
            context.startService(Intent(context, SingBoxVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
