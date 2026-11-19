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
import java.net.Socket
import java.net.SocketTimeoutException
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
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    @Volatile private var lastEvent: String? = null
    private var server: CommandServer? = null
    private var trafficClient: CommandClient? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var stopping = false
    @Volatile private var profile: String? = null
    private val lifecycle: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RahYab-singbox-lifecycle").apply { isDaemon = true }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
        currentInstance = this
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "وضعیت فیلتر محسن", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            beginStop(startId)
            return START_NOT_STICKY
        }
        val requestedProfile = intent?.getStringExtra(EXTRA_PROFILE)
        if (requestedProfile.isNullOrBlank() || stopping) {
            if (!stopping) stopSelf(startId)
            return START_NOT_STICKY
        }
        profile = requestedProfile
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
            if (stopping) return@enqueueLifecycle
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
                val transportCheck = runCatching { checkGeneratedRelayTcp(requestedProfile) }
                    .getOrElse { error ->
                        // A TCP reachability preflight is diagnostic, not definitive.
                        Log.w(TAG, "sing-box preflight unavailable", error)
                        "TCP_PREFLIGHT_UNAVAILABLE: ${error.javaClass.simpleName}"
                    }
                Libbox.setup(setup)
                val active = server
                if (active != null) {
                    active.startOrReloadService(requestedProfile, OverrideOptions())
                } else {
                    val platform = Proxy.newProxyInstance(PlatformInterface::class.java.classLoader, arrayOf(PlatformInterface::class.java), PlatformHandler()) as PlatformInterface
                    val callbacks = Proxy.newProxyInstance(CommandServerHandler::class.java.classLoader, arrayOf(CommandServerHandler::class.java), CommandHandler()) as CommandServerHandler
                    created = CommandServer(callbacks, platform)
                    created.start()
                    created.startOrReloadService(requestedProfile, OverrideOptions())
                    server = created
                    created = null
                }
                if (stopping) return@enqueueLifecycle
                startTrafficMonitor()
                Log.i(TAG, "libbox accepted the profile and started/reloaded its service")
                updateNotification("موتور VPN راه‌اندازی شد؛ وضعیت مسیر اینترنت در حال بررسی است")
                sendEvent(EVENT_ENGINE_STARTED, transportCheck)
            } catch (e: Exception) {
                Log.e(TAG, "sing-box startup failed", e)
                runCatching { created?.closeService() }
                runCatching { created?.close() }
                if (!stopping) {
                    updateNotification("خطای راه‌اندازی: " + (e.message ?: e.javaClass.simpleName))
                    stopEngine()
                    sendEvent(EVENT_ERROR, e.message ?: e.javaClass.simpleName)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Distinguish public relay unreachability from DNS/TUN failures.
     * At most two numeric-address TCP servers are tested before the core starts.
     * A negative preflight is diagnostic only; VPN protocols can behave
     * differently under real TLS and network conditions.
     * Never record the host, URL, key, SNI or profile in logs/broadcasts.
     */
    private fun checkGeneratedRelayTcp(json: String): String? {
        val root = JSONObject(json)
        if (root.optJSONObject("dns")?.optString("final") != "rah-yab-dns") return null
        val outbounds = root.optJSONArray("outbounds") ?: return "RELAY_TCP_NO_OUTBOUNDS"
        var tested = 0
        var skippedHostname = 0
        for (index in 0 until outbounds.length()) {
            if (tested >= 2 || stopping) break
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("type") !in setOf("vless", "vmess", "trojan")) continue
            val address = outbound.optString("server")
            val port = outbound.optInt("server_port")
            if (!address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
                    || port !in 1..65535) {
                skippedHostname++
                continue
            }
            tested++
            try {
                Socket().use { socket ->
                    socket.bind(InetSocketAddress(0))
                    if (!protect(socket)) return "RELAY_TCP_PROTECT_FAILED"
                    socket.connect(InetSocketAddress(address, port), 1500)
                    return "RELAY_TCP_REACHABLE"
                }
            } catch (_: java.io.IOException) {
                // The individual volunteer relay is not reachable over TCP.
            } catch (_: RuntimeException) {
                // Keep source-dependent network errors separate from libbox.
            }
        }
        return when {
            tested > 0 -> "RELAY_TCP_UNREACHABLE_" + tested + "_TESTS"
            skippedHostname > 0 -> "RELAY_TCP_HOSTNAME_NEEDS_DNS"
            else -> "RELAY_TCP_NO_COMPATIBLE_ENDPOINT"
        }
    }

    /**
     * A generated single-server VLESS profile can be checked before creating a VPN.
     * Custom routes, named hosts and transports with different dialers go to libbox unchanged.
     */
    private fun checkVlessServerTcp(json: String): String? {
        val root = JSONObject(json)
        val dns = root.optJSONObject("dns") ?: return null
        if (dns.optString("final") != "rah-yab-dns") return null
        val route = root.optJSONObject("route") ?: return null
        val outbounds = root.optJSONArray("outbounds") ?: return null
        if (outbounds.length() != 2) return null
        val outbound = outbounds.optJSONObject(0) ?: return null
        if (outbound.optString("type") != "vless" ||
            outbound.optString("tag") != route.optString("final") ||
            outbound.has("detour")) return null
        val transport = outbound.optJSONObject("transport")?.optString("type").orEmpty()
        if (transport !in setOf("", "ws", "grpc")) return null
        val address = outbound.optString("server")
        if (!address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null
        val port = outbound.optInt("server_port")
        if (port !in 1..65535) return null

        val startedAt = android.os.SystemClock.elapsedRealtime()
        Socket().use { socket ->
            socket.bind(InetSocketAddress(0))
            check(protect(socket)) { "آزمون TCP انجام نشد: اندروید سوکت آزمایشی را از VPN جدا نکرد" }
            try {
                socket.connect(InetSocketAddress(address, port), 5000)
            } catch (_: SocketTimeoutException) {
                error("سرور VLESS در آزمون TCP پیش از ساخت تونل تا ۵ ثانیه پاسخ نداد")
            } catch (e: java.io.IOException) {
                error("آزمون TCP سرور VLESS پیش از ساخت تونل ناموفق بود (${e.javaClass.simpleName})")
            }
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        return "TCP_SERVER_REACHABLE | ${elapsed} ms | TLS و عبور اینترنت جداگانه بررسی می‌شوند"
    }

    private inner class PlatformHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val a = args ?: emptyArray()
            return when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = a[0] as Int
                    check(protect(fd)) { "اندروید نتوانست سوکت سرور sing-box را از تونل جدا کند" }
                    null
                }
                "openTun" -> openTun(a[0] as TunOptions)
                "getInterfaces" -> getInterfaces()
                "findConnectionOwner" -> findConnectionOwner(
                    a[0] as Int, a[1] as String, a[2] as Int, a[3] as String, a[4] as Int
                )
                "startDefaultInterfaceMonitor" -> {
                    startInterfaceMonitor(a[0] as InterfaceUpdateListener)
                    null
                }
                "closeDefaultInterfaceMonitor" -> {
                    closeInterfaceMonitor()
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

    private fun startInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeInterfaceMonitor()
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun update() {
                if (networkCallback !== this || stopping) return
                val physical = (listOfNotNull(manager.activeNetwork) + manager.allNetworks)
                    .distinct().mapNotNull { network ->
                        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                        val name = manager.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
                        val index = runCatching { JavaNetworkInterface.getByName(name)?.index }.getOrNull()
                            ?: return@mapNotNull null
                        Triple(name, index, capabilities)
                    }.maxByOrNull { (_, _, capabilities) ->
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0
                    }
                if (physical == null) {
                    listener.updateDefaultInterface("", -1, false, false)
                } else {
                    val (name, index, capabilities) = physical
                    listener.updateDefaultInterface(name, index,
                        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), false)
                }
            }

            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update()
        }
        networkCallback = callback
        manager.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
    }

    private fun closeInterfaceMonitor() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
    }

    private fun startTrafficMonitor() {
        if (trafficClient != null) return
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            statusInterval = 1_000_000_000L
        }
        val handler = Proxy.newProxyInstance(CommandClientHandler::class.java.classLoader,
            arrayOf(CommandClientHandler::class.java)) { proxy, method, args ->
            when (method.name) {
                "writeStatus" -> {
                    val status = args?.firstOrNull() as? StatusMessage
                    if (status != null && status.trafficAvailable && !stopping) {
                        runCatching { writeTraffic(status.downlinkTotal, status.uplinkTotal) }
                    }
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                else -> null
            }
        } as CommandClientHandler
        runCatching {
            val client = CommandClient(handler, options)
            try {
                client.connect()
                trafficClient = client
            } catch (error: Exception) {
                runCatching { client.disconnect() }
                throw error
            }
        }.onFailure { Log.w(TAG, "Traffic monitor unavailable", it) }
    }

    private fun writeTraffic(received: Long, sent: Long) {
        val file = AtomicFile(trafficPath(this))
        val output = file.startWrite()
        try {
            java.io.DataOutputStream(output).apply {
                writeLong(android.os.SystemClock.elapsedRealtime())
                writeLong(received)
                writeLong(sent)
                flush()
            }
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
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
        check(!stopping) { "VPN is stopping" }
        if (VpnService.prepare(this) != null) error("مجوز VPN صادر نشده است")
        val builder = Builder().setSession("فیلتر محسن").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        var assignedAddresses = 0
        var advertisedDns = 0
        var advertisedRoutes = 0
        val generatedDns = runCatching {
            JSONObject(profile.orEmpty()).optJSONObject("dns")?.optString("final") == "rah-yab-dns"
        }.getOrDefault(false)
        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val address = v4.next()
            builder.addAddress(address.address(), address.prefix())
            assignedAddresses++
        }
        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val address = v6.next()
            builder.addAddress(address.address(), address.prefix())
            assignedAddresses++
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) {
                    builder.addDnsServer(dns.next())
                    advertisedDns++
                }
                if (advertisedDns == 0 && generatedDns) {
                    builder.addDnsServer("172.19.0.2")
                    advertisedDns++
                }
            }
            val r4 = options.inet4RouteRange
            while (r4.hasNext()) {
                val route = r4.next()
                builder.addRoute(route.address(), route.prefix())
                advertisedRoutes++
            }
            val r6 = options.inet6RouteRange
            while (r6.hasNext()) {
                val route = r6.next()
                builder.addRoute(route.address(), route.prefix())
                advertisedRoutes++
            }
            if (advertisedRoutes == 0 && generatedDns) {
                builder.addRoute("0.0.0.0", 0)
                advertisedRoutes++
            }
            val included = options.includePackage
            while (included.hasNext()) builder.addAllowedApplication(included.next())
            val excluded = options.excludePackage
            while (excluded.hasNext()) builder.addDisallowedApplication(excluded.next())
        }

        val descriptor = builder.establish() ?: error("Android نتوانست تونل VPN را بسازد")
        tunnel?.close()
        tunnel = descriptor
        sendEvent(EVENT_TUN_ESTABLISHED,
            "TUN_PARAMETERS addresses=$assignedAddresses dns=$advertisedDns routes=$advertisedRoutes")
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

    @Synchronized
    private fun closeTunnel() {
        runCatching { tunnel?.close() }
        tunnel = null
    }

    private fun stopEngine() {
        closeTunnel()
        // libbox duplicates the TUN descriptor, so close its service before optional cleanup.
        runCatching { server?.closeService() }.onFailure { Log.w(TAG, "closeService", it) }
        runCatching { trafficClient?.disconnect() }
        trafficClient = null
        trafficPath(this).delete()
        closeInterfaceMonitor()
        runCatching { server?.close() }.onFailure { Log.w(TAG, "close", it) }
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun sendEvent(event: String, detail: String? = null) {
        // SharedPreferences do not provide coherent reads across processes.
        runCatching { persistEvent(event, detail) }
            .onFailure { Log.w(TAG, "Could not persist sing-box status", it) }
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
            .setContentTitle("فیلتر محسن VPN")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }

    private fun beginStop(startId: Int? = null) {
        if (stopping) return
        stopping = true
        val deadline = Runnable {
            if (currentInstance !== this) return@Runnable
            sendEvent(EVENT_STOPPED, "مهلت توقف موتور تمام شد؛ فرایند جداگانه sing-box بسته شد")
            // Only this service uses the dedicated :singbox process. Closing the process
            // releases libbox's duplicated TUN descriptor if native cleanup is stuck.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        mainHandler.postDelayed(deadline, 4_000L)
        closeTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        enqueueLifecycle {
            stopEngine()
            mainHandler.post {
                if (currentInstance !== this) return@post
                mainHandler.removeCallbacks(deadline)
                sendEvent(EVENT_STOPPED)
                currentInstance = null
                if (startId != null && stopSelfResult(startId)) return@post
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
    override fun onRevoke() {
        mainHandler.post { beginStop() }
        super.onRevoke()
    }
    override fun onDestroy() {
        if (!stopping && lastEvent != EVENT_ERROR) beginStop()
        lifecycle.shutdown()
        super.onDestroy()
    }

    companion object {
        private var currentInstance: SingBoxVpnService? = null
        private const val TAG = "RahYabSingBox"
        private const val KEY_LAST_EVENT = "last_event"
        private const val KEY_LAST_DETAIL = "last_detail"
        private const val KEY_LAST_EVENT_TIME = "last_event_time"

        private fun trafficPath(context: Context) = File(context.cacheDir, "singbox-traffic.bin")

        @JvmStatic
        fun tunnelBytes(context: Context): LongArray? = runCatching {
            java.io.DataInputStream(trafficPath(context).inputStream()).use { stream ->
                val age = android.os.SystemClock.elapsedRealtime() - stream.readLong()
                if (age !in 0..3_000) null else longArrayOf(stream.readLong(), stream.readLong())
            }
        }.getOrNull()

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
        fun requestDisconnect(context: Context) {
            val intent = Intent(context, SingBoxVpnService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                stop(context)
            }
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
