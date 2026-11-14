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
import kotlinx.coroutines.*

class SstpVpnService : VpnService() {
    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var controller: Controller? = null
    private var candidates = ArrayDeque<String>()
    @Volatile private var generation = 0
    private var active = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != "sstp.start" || active) {
            if (!active) stopSelf(startId)
            return START_NOT_STICKY
        }
        val hosts = intent.getStringArrayListExtra("hosts").orEmpty()
            .filter { it.matches(Regex("(?i)[a-z0-9-]+\\.opengw\\.net")) }.distinct().take(4)
        if (hosts.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sstp", "راه‌یاب SSTP", NotificationManager.IMPORTANCE_LOW))
        startForeground(7510, Notification.Builder(this, "sstp")
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("راه‌یاب SSTP")
            .setContentText("در حال آزمون اتصال SSTP").setOngoing(true).build())
        candidates = ArrayDeque(hosts)
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
        val host = candidates.removeFirstOrNull()
        if (host == null) {
            active = false
            publish("هیچ سرور SSTP آزمون اینترنت را پاس نکرد", "failed")
            stopSelf()
            return
        }
        val token = ++generation
        getSharedPreferences("sstp_engine", MODE_PRIVATE).edit().clear()
            .putString(OscPrefKey.HOME_HOSTNAME.name, host)
            .putString(OscPrefKey.HOME_USERNAME.name, "vpn")
            .putString(OscPrefKey.HOME_PASSWORD.name, "vpn").apply()
        publish("در حال آزمایش SSTP — $host", "searching")
        controller = Controller(SharedBridge(this, token)).also { it.launchJobMain() }
        main.postDelayed({
            if (token == generation && active &&
                getSharedPreferences("rah_yab_diagnostics", MODE_PRIVATE)
                    .getString("connection_lamp_state", "") == "searching") {
                controller?.kill(false, null)
            }
        }, 28_000L)
    }

    internal fun verifyTunnel(token: Int) {
        scope.launch {
            val result = TunnelVerifier.verify(this@SstpVpnService)
            main.post {
                if (!active || token != generation) return@post
                if (result.verified) publish("عبور اینترنت با SSTP تأیید شد", "connected")
                else controller?.kill(false, null)
            }
        }
    }

    internal fun notifyError(message: String) {
        android.util.Log.w("RahYabSSTP", message)
    }

    internal fun close(token: Int) {
        main.post {
            if (active && token == generation) {
                controller = null
                tryNext()
            }
        }
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (active) publish("اتصال SSTP قطع شد", "disconnected")
        active = false
        generation++
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        controller?.closeTerminals()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
