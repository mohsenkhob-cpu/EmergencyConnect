package com.rahyab.next

import android.content.Context
import android.os.Bundle

/**
 * Compatibility-only placeholder for saved legacy code paths.
 * The libbox native engine and VpnService declaration have been removed.
 */
class SingBoxVpnService private constructor() {
    companion object {
        const val ACTION_EVENT = "com.rahyab.next.SINGBOX_EVENT"
        const val EXTRA_EVENT = "rahyab.event"
        const val EXTRA_DETAIL = "rahyab.detail"

        @JvmStatic fun start(context: Context, profile: String) {
            throw IllegalStateException("این موتور از نسخه سبک فیلتر محسن حذف شده است")
        }
        @JvmStatic fun stop(context: Context) { }
        @JvmStatic fun requestDisconnect(context: Context) { }
        @JvmStatic fun readStatus(context: Context): Bundle = Bundle()
        @JvmStatic fun tunnelBytes(context: Context): LongArray? = null
    }
}
