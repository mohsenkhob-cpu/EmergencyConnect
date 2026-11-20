package kittoku.osc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import com.rahyab.next.TunnelVerifier
import kittoku.osc.SharedBridge
import kittoku.osc.control.Controller
import kittoku.osc.preference.OscPrefKey
import ir.emergencyconnect.engine.RecentRelayStore
import kotlinx.coroutines.*

class SstpVpnService : VpnService() {
    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var controller: Controller? = null
    private var candidates = ArrayDeque<String>()
    @Volatile private var generation = 0
    private var active = false
    private var verified = false
    private var bestHost: String? = null
    private var bestSpeed = -1L
    private var restoringBest = false
    private var attemptMemoryKey = ""
    private var currentHost = ""
    private var currentStage = ""
    private val diagnostics by lazy { getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "sstp.stop") {
            shutdown()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != "sstp.start" || active) {
            if (!active) stopSelf(startId)
            return START_NOT_STICKY
        }
        val hosts = intent.getStringArrayListExtra("hosts").orEmpty()
            .mapNotNull { endpoint ->
                val host = endpoint.substringBefore(':')
                val port = endpoint.substringAfter(':', "443").toIntOrNull()
                if (host.matches(Regex("(?i)[a-z0-9-]+\\.opengw\\.net")) && port != null
                    && port in 1..65535) "$host:$port" else null
            }.distinct().take(4)
        if (hosts.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sstp", "فیلتر محسن SSTP", NotificationManager.IMPORTANCE_LOW))
        startForeground(7510, Notification.Builder(this, "sstp")
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("فیلتر محسن SSTP")
            .setContentText("در حال آزمون اتصال SSTP").setOngoing(true).build())
        diagnostics.edit().remove("sstp_attempt_results").apply()
        candidates = ArrayDeque(hosts)
        attemptMemoryKey = intent.getStringExtra("sstp_memory_key").orEmpty()
            .takeIf { it in setOf("wifi", "cell_mci", "cell_irancell", "cell_other") }.orEmpty()
        active = true
        tryNext()
        return START_NOT_STICKY
    }

    private fun publish(message: String, lamp: String) {
        getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE).edit()
            .putString("service_status_message", message).putString("connection_lamp_state", lamp)
            .putLong("service_status_updated_at", System.currentTimeMillis()).apply()
    }

    private fun tryNext() {
        if (!active) return
        var host = candidates.removeFirstOrNull()
        if (host == null && !restoringBest) {
            host = bestHost
            restoringBest = host != null
        }
        if (host == null) {
            active = false
            publish("اتصال SSTP تأیید نشد؛ نتیجه هر میزبان در گزارش است", "failed")
            stopSelf()
            return
        }
        val token = ++generation
        verified = false
        traffic = Traffic(token)
        currentHost = host
        currentStage = "آماده‌سازی موتور"
        getSharedPreferences("sstp_engine", MODE_PRIVATE).edit().clear()
            .putString(OscPrefKey.HOME_HOSTNAME.name, host.substringBefore(':'))
            .putString(OscPrefKey.SSL_PORT.name, host.substringAfter(':'))
            .putString(OscPrefKey.HOME_USERNAME.name, "vpn")
            .putString(OscPrefKey.HOME_PASSWORD.name, "vpn").apply()
        publish("در حال آزمایش SSTP — $host", "searching")
        controller = Controller(SharedBridge(this, token)).also { it.launchJobMain() }
        main.postDelayed({
            if (token == generation && active && !verified) {
                recordResult("مهلت آزمون تمام شد")
                controller?.kill(false, null)
            }
        }, 28_000L)
    }

    internal fun verifyTunnel(token: Int) {
        stage(token, "آزمون اینترنت")
        scope.launch {
            val result = TunnelVerifier.verify(this@SstpVpnService)
            val speed = if (result.verified && token == generation)
                TunnelVerifier.sampleDownloadKbps(this@SstpVpnService) else -1L
            main.post {
                if (!active || token != generation) return@post
                if (result.verified) {
                    verified = true
                    val speedLabel = if (speed > 0) "نمونهٔ دانلود: $speed کیلوبیت‌برثانیه"
                        else "نمونهٔ دانلود در دسترس نبود"
                    recordResult("عبور اینترنت تأیید شد؛ $speedLabel")
                    // A successful HTTPS tunnel must not be torn down because
                    // a tiny download sample fell below an arbitrary threshold.
                    // Continuity matters more than speculative speed ranking.
                    if (attemptMemoryKey.isNotEmpty() && currentHost.isNotEmpty()) {
                        getSharedPreferences("sstp_recent_good", MODE_PRIVATE).edit()
                            .putString("host_$attemptMemoryKey", currentHost)
                            .putLong("saved_at_$attemptMemoryKey", System.currentTimeMillis()).apply()
                    }
                    bestHost = null
                    bestSpeed = -1L
                    restoringBest = false
                    publish("عبور اینترنت با SSTP تأیید شد و اتصال حفظ شد؛ $speedLabel", "connected")
                } else {
                    recordResult(result.message)
                    controller?.kill(false, null)
                }
            }
        }
    }

    private fun recordResult(message: String) {
        val prior = diagnostics.getString("sstp_attempt_results", "").orEmpty()
        diagnostics.edit().putString("sstp_attempt_results",
            (prior + "$currentHost — $currentStage — $message\n").takeLast(4096)).apply()
    }

    internal fun stage(token: Int, label: String) {
        main.post { if (active && token == generation) currentStage = label }
    }

    internal fun notifyError(token: Int, message: String) {
        main.post { if (active && token == generation) recordResult(message) }
    }

    internal fun close(token: Int) {
        main.post {
            if (active && token == generation) {
                // Distinguish a remote session drop *after* verified HTTPS
                // from a relay that never carried traffic. Do not expose keys.
                if (verified) {
                    recordResult("نشست پس از تأیید HTTPS بسته شد؛ در حال آزمون مسیر جایگزین")
                    publish("SSTP قبلاً اینترنت داشت، اما نشست بسته شد؛ آزمون سرور بعدی…",
                        "searching")
                } else {
                    recordResult("نشست پیش از تأیید HTTPS بسته شد")
                }
                controller = null
                tryNext()
            }
        }
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    private fun shutdown() {
        val wasActive = active
        active = false
        verified = false
        generation++
        traffic = null
        candidates.clear()
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        controller?.shutdown()
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (wasActive) publish("اتصال SSTP قطع شد", "disconnected")
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    internal fun countReceived(token: Int, bytes: Int) {
        traffic?.takeIf { it.token == token }?.received?.addAndGet(bytes.toLong())
    }

    internal fun countSent(token: Int, bytes: Int) {
        traffic?.takeIf { it.token == token }?.sent?.addAndGet(bytes.toLong())
    }

    private class Traffic(val token: Int) {
        val received = java.util.concurrent.atomic.AtomicLong()
        val sent = java.util.concurrent.atomic.AtomicLong()
    }

    companion object {
        @Volatile private var traffic: Traffic? = null

        @JvmStatic fun tunnelBytes(): LongArray? =
            traffic?.let { longArrayOf(it.received.get(), it.sent.get()) }
    }
}
