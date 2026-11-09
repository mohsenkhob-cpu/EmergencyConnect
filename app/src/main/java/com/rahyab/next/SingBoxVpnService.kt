package com.rahyab.next

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Minimal Android host for the official libbox engine.
 * Optional root/shell/bridge integrations are intentionally not enabled.
 */
class SingBoxVpnService : VpnService() {
    @Synchronized
    private fun persistEvent(event: String, detail: String?) {
        lastEvent = event
        val file = statusFile(this)
        val output = file.startWrite()
        try {
            val value = JSONObject().put(KEY_LAST_EVENT, event)
                .put(KEY_LAST_DETAIL, detail.orEmpty())
                .put(KEY_LAST_EVENT_TIME, System.currentTimeMillis())
                .put("probe_result", lastProbe)
                .put("recovery_count", recoveryCount)
                .put("resolver_index", currentResolver)
                .put("physical_network", physicalNetwork()?.let { NetworkDiagnostics.describe(this, it) }.orEmpty())
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    @Volatile private var lastEvent: String? = null
    private var server: CommandServer? = null
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var profile: String? = null
    private val lifecycle: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RahYab-singbox-lifecycle").apply { isDaemon = true }
    }
    @Volatile private var verificationEpoch = 0
    @Volatile private var preferredPhysical: Network? = null
    private val verifier = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var networkWaitSince = 0L
    private var recoveryCount = 0
    private var currentResolver = 0
    private var lastProbe = ""
    private var verificationTask: java.util.concurrent.Future<*>? = null
    private fun isAutomatic() = profile?.contains("rahyab-auto") == true
    private val channel = "rahyab-singbox"
    private val notificationId = 7301

    // Native callbacks can arrive while Android is destroying the service.
    // Ignore late callbacks instead of crashing on a shut-down executor.
    private fun enqueueLifecycle(task: () -> Unit) {
        try {
            lifecycle.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Ignoring late sing-box lifecycle callback", e)
        }
    }

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
            enqueueLifecycle { stopEngine(); sendEvent(EVENT_STOPPED); stopSelf(startId) }
            return START_NOT_STICKY
        }
        val requestedProfile = intent?.getStringExtra(EXTRA_PROFILE)
        if (requestedProfile.isNullOrBlank()) { stopSelf(startId); return START_NOT_STICKY }
        verificationEpoch++
        verificationTask?.cancel(true)
        profile = requestedProfile
        recoveryCount = 0
        networkWaitSince = 0L
        currentResolver = when (runCatching {
            JSONObject(requestedProfile).getJSONObject("dns").getJSONArray("servers")
                .getJSONObject(0).getString("server")
        }.getOrDefault("")) { "8.8.8.8" -> 1; "9.9.9.9" -> 2; else -> 0 }
        physicalNetwork()?.let { preferredPhysical = it; setUnderlyingNetworks(arrayOf(it)) }
        try {
            startForeground(notificationId, notification("در حال راه‌اندازی موتور VPN…"))
        } catch (e: Exception) {
            Log.e(TAG, "could not promote VPN service to foreground", e)
            sendEvent(EVENT_ERROR, "اندروید اجازه شروع سرویس VPN را نداد: " + (e.message ?: e.javaClass.simpleName))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        sendEvent(EVENT_STARTING)
        enqueueLifecycle {
            var created: CommandServer? = null
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
                val launchProfile = if (isAutomatic()) {
                    val root = JSONObject(requestedProfile)
                    val runtimeLog = File(filesDir, "singbox-runtime.log")
                    runtimeLog.writeText("")
                    root.getJSONObject("log").put("output", runtimeLog.absolutePath).put("level", "warn")
                    root.toString()
                } else requestedProfile
                val active = server
                if (active != null) {
                    active.startOrReloadService(launchProfile, OverrideOptions())
                } else {
                    val platform = Proxy.newProxyInstance(PlatformInterface::class.java.classLoader, arrayOf(PlatformInterface::class.java), PlatformHandler()) as PlatformInterface
                    val callbacks = Proxy.newProxyInstance(CommandServerHandler::class.java.classLoader, arrayOf(CommandServerHandler::class.java), CommandHandler()) as CommandServerHandler
                    created = CommandServer(callbacks, platform)
                    created.start()
                    created.startOrReloadService(launchProfile, OverrideOptions())
                    server = created
                    created = null
                }
                Log.i(TAG, "libbox accepted the profile and started/reloaded its service")
                updateNotification("موتور VPN راه‌اندازی شد؛ وضعیت مسیر اینترنت در حال بررسی است")
                sendEvent(EVENT_ENGINE_STARTED)
                if (isAutomatic()) {
                    sendEvent("AUTO_SEARCHING", "sing-box در حال آزمون مسیرهای VLESS/Trojan و عبور واقعی اینترنت است…")
                    beginAutomaticVerification(verificationEpoch, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "sing-box startup failed", e)
                runCatching { created?.closeService() }
                runCatching { created?.close() }
                updateNotification("خطای راه‌اندازی: " + (e.message ?: e.javaClass.simpleName))
                stopEngine()
                sendEvent(EVENT_ERROR, e.message ?: e.javaClass.simpleName)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun physicalNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val candidates = cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (active in candidates) return active
        preferredPhysical?.let { if (it in candidates) return it }
        return candidates.maxByOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            val dnsReady = !cm.getLinkProperties(network)?.dnsServers.isNullOrEmpty()
            (if (dnsReady) 4 else 0) +
                (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 2 else 0)
        }
    }

    private inner class PlatformHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val a = args ?: emptyArray()
            return when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = a[0] as Int
                    check(protect(fd)) { "اندروید نتوانست سوکت سرور sing-box را از تونل جدا کند" }
                    val network = physicalNetwork() ?: error("شبکه زیرین VPN در دسترس نیست")
                    // Bind the protected socket itself, never the whole app process.
                    ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
                    Log.i(TAG, "Physical transport socket protected and bound")
                    null
                }
                "openTun" -> openTun(a[0] as TunOptions)
                "getInterfaces" -> getInterfaces()
                "findConnectionOwner" -> findConnectionOwner(
                    a[0] as Int, a[1] as String, a[2] as Int, a[3] as String, a[4] as Int
                )
                "startDefaultInterfaceMonitor" -> {
                    startDefaultInterfaceMonitor(a[0] as InterfaceUpdateListener)
                    null
                }
                "closeDefaultInterfaceMonitor" -> {
                    stopDefaultInterfaceMonitor()
                    null
                }
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

    private fun findConnectionOwner(
        protocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int,
    ): ConnectionOwner {
        // libbox dereferences the result even when Android cannot identify the owner.
        // Use an explicit unknown UID on older Android or when the socket is already gone.
        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                getSystemService(ConnectivityManager::class.java)?.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort),
                ) ?: -1
            } catch (_: RuntimeException) {
                -1
            }
        } else {
            -1
        }
        val packages = if (uid >= 0) packageManager.getPackagesForUid(uid).orEmpty()
            else emptyArray<String>()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(iterator(packages.toList(), StringIterator::class.java))
        }
    }

    private inner class CommandHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "serviceStop" -> {
                    enqueueLifecycle { stopEngine(); sendEvent(EVENT_STOPPED); stopSelf() }
                    null
                }
                "serviceReload" -> {
                    val currentProfile = profile
                    if (!currentProfile.isNullOrBlank()) enqueueLifecycle {
                        runCatching { server?.startOrReloadService(currentProfile, OverrideOptions()) }
                            .onFailure { Log.e(TAG, "sing-box reload failed", it) }
                    }
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

    @Synchronized
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
                val hostAddress = host.hostAddress ?: return@mapNotNull null
                val ip = if (host is Inet6Address) hostAddress.substringBefore('%') else hostAddress
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
                flags = (if (item.isUp) 1 or 64 else 0) or
                    (if (item.supportsMulticast()) 4096 else 0) or
                    (if (item.isPointToPoint) 16 else 2)
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

    @Volatile
    private var defaultInterfaceCallback: ConnectivityManager.NetworkCallback? = null

    private fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (Build.VERSION.SDK_INT < 31) return
        stopDefaultInterfaceMonitor()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var currentNetwork: Network? = null

            override fun onAvailable(network: Network) {
                currentNetwork = network
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (defaultInterfaceCallback !== this || currentNetwork != network) return
                val name = properties.interfaceName ?: return
                val index = runCatching { JavaNetworkInterface.getByName(name)?.index }.getOrNull() ?: return
                if (index > 0) {
                    preferredPhysical = network
                    setUnderlyingNetworks(arrayOf(network))
                    listener.updateDefaultInterface(name, index, false, false)
                }
            }

            override fun onLost(network: Network) {
                if (defaultInterfaceCallback !== this || currentNetwork != network) return
                currentNetwork = null
                listener.updateDefaultInterface("", -1, false, false)
            }
        }
        defaultInterfaceCallback = callback
        try {
            connectivity.registerBestMatchingNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            defaultInterfaceCallback = null
            throw e
        }
    }

    private fun stopDefaultInterfaceMonitor() {
        val callback = defaultInterfaceCallback ?: return
        defaultInterfaceCallback = null
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
    }

    private fun stopEngine() {
        verificationEpoch++
        verificationTask?.cancel(true)
        runCatching { server?.closeService() }.onFailure { Log.w(TAG, "closeService", it) }
        runCatching { server?.close() }.onFailure { Log.w(TAG, "close", it) }
        server = null
        runCatching { stopDefaultInterfaceMonitor() }.onFailure { Log.w(TAG, "close network monitor", it) }
        synchronized(this) {
            runCatching { tunnel?.close() }
            tunnel = null
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun beginAutomaticVerification(epoch: Int, monitoring: Boolean) {
        verificationTask = verifier.submit {
            var result: TunnelVerifier.Result? = null
            // URLTest probes candidates concurrently; allow its first selection to settle.
            for (attempt in 0 until 2) {
                if (epoch != verificationEpoch || Thread.currentThread().isInterrupted) return@submit
                if (!monitoring) sendEvent("AUTO_SEARCHING", "آزمون مستقل اینترنت؛ تلاش " + (attempt + 1) + " از ۲")
                result = TunnelVerifier.verify(this)
                lastProbe = result.message
                if (result.verified) break
                if (attempt < 1) {
                    try { Thread.sleep(2500L) } catch (_: InterruptedException) { return@submit }
                }
            }
            if (epoch != verificationEpoch || Thread.currentThread().isInterrupted) return@submit
            if (result?.verified == true) {
                runCatching { saveSuccessfulPool() }
                val message = "اتصال جایگزین sing-box تأیید شد؛ آزمون مستقل HTTPS موفق بود"
                sendEvent("AUTO_CONNECTED", message)
                updateNotification(message)
                verificationTask = verifier.schedule({
                    if (epoch == verificationEpoch) beginAutomaticVerification(epoch, true)
                }, 45L, java.util.concurrent.TimeUnit.SECONDS)
            } else {
                if (recoveryCount < 3) {
                    recoverAutomatic(epoch, result?.dnsOnlyFailure == true)
                } else {
                    val message = "سه نوبت بازیابی انجام شد؛ اتصال پایدار تأیید نشد؛ " + lastProbe
                    enqueueLifecycle {
                        if (epoch == verificationEpoch) {
                            stopEngine()
                            sendEvent("AUTO_FAILED", message)
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    private fun saveSuccessfulPool() {
        val current = profile ?: return
        val value = JSONObject().put("saved_at", System.currentTimeMillis())
            .put("network_key", physicalNetwork()?.let { poolNetworkKey(this, it) }.orEmpty())
            .put("profile", current)
        val file = AtomicFile(File(filesDir, "singbox-success-pool.json"))
        val output = file.startWrite()
        try { output.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    private fun recoverAutomatic(epoch: Int, dnsOnly: Boolean) {
        if (epoch != verificationEpoch || Thread.currentThread().isInterrupted) return
        if (physicalNetwork() == null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (networkWaitSince == 0L) networkWaitSince = now
            if (now - networkWaitSince < 120000L) {
                sendEvent("AUTO_SEARCHING", "شبکه زیرین قطع است؛ در انتظار بازگشت شبکه و بازیابی خودکار…")
                verificationTask = verifier.schedule({ recoverAutomatic(epoch, dnsOnly) },
                    10L, java.util.concurrent.TimeUnit.SECONDS)
            } else enqueueLifecycle {
                if (epoch == verificationEpoch) {
                    stopEngine(); sendEvent("AUTO_FAILED", "شبکه زیرین تا پایان مهلت دو دقیقه‌ای برنگشت"); stopSelf()
                }
            }
            return
        }
        networkWaitSince = 0L
        recoveryCount++
        currentResolver = (currentResolver + 1) % 3
        sendEvent("AUTO_SEARCHING", if (dnsOnly) "مسیر HTTPS برقرار است؛ بازیابی DNS با حل‌کننده جایگزین…"
            else "بازیابی خودکار مسیر؛ نوبت " + recoveryCount + " از ۳؛ دریافت مجموعه تازه…")
        val next = runCatching {
            val base = if (dnsOnly) profile ?: error("No profile") else {
                val network = physicalNetwork() ?: error("No physical network")
                PublicProxyProvider.fetch(
                    { url -> network.openConnection(url, java.net.Proxy.NO_PROXY) },
                    { epoch != verificationEpoch || Thread.currentThread().isInterrupted }
                ).profile
            }
            PublicProxyProvider.withResolver(base, currentResolver)
        }.getOrElse {
            // Re-probe the retained pool if discovery is temporarily unavailable.
            if (epoch != verificationEpoch || Thread.currentThread().isInterrupted) return
            runCatching { PublicProxyProvider.withResolver(profile ?: return, currentResolver) }
                .getOrNull() ?: run { sendEvent("AUTO_FAILED", "بازیابی تنظیمات ناموفق بود"); return }
        }
        enqueueLifecycle {
            if (epoch != verificationEpoch) return@enqueueLifecycle
            try {
                profile = next
                server?.startOrReloadService(next, OverrideOptions()) ?: error("Engine unavailable")
                sendEvent("AUTO_SEARCHING", "مسیر بازیابی شد؛ بررسی عبور اینترنت؛ نوبت " + recoveryCount + " از ۳")
                beginAutomaticVerification(epoch, false)
            } catch (error: Exception) {
                stopEngine()
                sendEvent("AUTO_FAILED", "بارگذاری مسیر جایگزین ناموفق بود")
                stopSelf()
            }
        }
    }

    private fun sendEvent(event: String, detail: String? = null) {
        val publishedEvent = if (isAutomatic() && event == EVENT_ERROR) "AUTO_FAILED"
            else if (isAutomatic() && event == EVENT_STOPPED) "AUTO_STOPPED" else event
        val publishedDetail = if (publishedEvent == "AUTO_STOPPED") "اتصال جایگزین متوقف شد"
            else if (publishedEvent == "AUTO_FAILED" && event == EVENT_ERROR)
                "راه‌اندازی موتور جایگزین ناموفق بود؛ گزارش موتور را بررسی کنید"
            else detail
        // SharedPreferences do not provide coherent reads across processes.
        runCatching { persistEvent(publishedEvent, publishedDetail) }
            .onFailure { Log.w(TAG, "Could not persist sing-box status", it) }
        val intent = Intent(ACTION_EVENT).setPackage(packageName)
            .putExtra(EXTRA_EVENT, publishedEvent)
        if (publishedDetail != null) intent.putExtra(EXTRA_DETAIL, publishedDetail)
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
        enqueueLifecycle { stopEngine(); sendEvent(EVENT_STOPPED); stopSelf() }
        super.onRevoke()
    }
    override fun onDestroy() {
        verificationEpoch++
        verificationTask?.cancel(true)
        verifier.shutdownNow()
        enqueueLifecycle {
            stopEngine()
            if (lastEvent != EVENT_ERROR && lastEvent != EVENT_STOPPED && lastEvent != "AUTO_FAILED" && lastEvent != "AUTO_STOPPED") sendEvent(EVENT_STOPPED)
        }
        lifecycle.shutdown()
        super.onDestroy()
    }

    companion object {
        @JvmStatic
        fun poolNetworkKey(context: Context, network: Network): String {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(network)
            val kind = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "wifi" else "cellular"
            return kind + ":" + cm.getLinkProperties(network)?.dnsServers.orEmpty()
                .map { it.hostAddress }.sorted().joinToString(",")
        }

        @JvmStatic
        fun recentSuccessfulPool(context: Context, network: Network): String? = runCatching {
            val value = JSONObject(File(context.filesDir, "singbox-success-pool.json").readText())
            val age = System.currentTimeMillis() - value.getLong("saved_at")
            if (age !in 0L..21600000L || value.getString("network_key") != poolNetworkKey(context, network)) null
            else value.getString("profile")
        }.getOrNull()

        private const val TAG = "RahYabSingBox"
        private const val KEY_LAST_EVENT = "last_event"
        private const val KEY_LAST_DETAIL = "last_detail"
        private const val KEY_LAST_EVENT_TIME = "last_event_time"

        private fun statusPath(context: Context) = File(context.filesDir, "singbox-status.json")

        private fun statusFile(context: Context) = AtomicFile(statusPath(context))

        @JvmStatic
        fun readStatus(context: Context): Bundle {
            val status = Bundle()
            runCatching {
                // Read only the published file. AtomicFile.openRead may remove a writer's
                // temporary file when the reader runs in a different process.
                val value = JSONObject(statusPath(context).readText(Charsets.UTF_8))
                status.putString(KEY_LAST_EVENT, value.optString(KEY_LAST_EVENT))
                status.putString(KEY_LAST_DETAIL, value.optString(KEY_LAST_DETAIL))
                status.putLong(KEY_LAST_EVENT_TIME, value.optLong(KEY_LAST_EVENT_TIME))
                status.putString("probe_result", value.optString("probe_result"))
                status.putString("physical_network", value.optString("physical_network"))
                status.putInt("recovery_count", value.optInt("recovery_count"))
                status.putInt("resolver_index", value.optInt("resolver_index"))
            }
            return status
        }
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
            // Do not start an idle service just to stop it: that creates a
            // spurious STOPPED broadcast which can overwrite another engine's
            // startup status. stopService() still invokes onDestroy() for an
            // active started service, where stopEngine() performs cleanup.
            context.stopService(Intent(context, SingBoxVpnService::class.java))
        }
    }
}


