package kittoku.osc.terminal

import android.os.ParcelFileDescriptor
import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.extension.toHexByteArray
import kittoku.osc.preference.LIST_TYPE_ALLOWED
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.net.InetAddress
import java.nio.ByteBuffer


internal class IPTerminal(private val bridge: SharedBridge) {
    private var fd: ParcelFileDescriptor? = null

    private var closed = false

    private val doEnableAppBasedRule = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, bridge.prefs)
    private val isAllowedList = getStringPrefValue(OscPrefKey.ROUTE_APP_LIST_TYPE, bridge.prefs) == LIST_TYPE_ALLOWED
    private val doAddDefaultRoute = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ADD_DEFAULT_ROUTE, bridge.prefs)
    private val doRoutePrivateAddresses = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ROUTE_PRIVATE_ADDRESSES, bridge.prefs)
    private val doUseCustomDNSServer = getBooleanPrefValue(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER, bridge.prefs)
    private val doAddCustomRoutes = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ADD_CUSTOM_ROUTES, bridge.prefs)

    internal suspend fun initialize() {
        if (bridge.PPP_IPv4_ENABLED) {
            if (bridge.currentIPv4.contentEquals(ByteArray(4))) {
                bridge.controlMailbox.send(ControlMessage(Where.IPv4, Result.ERR_INVALID_ADDRESS))
                return
            }

            InetAddress.getByAddress(bridge.currentIPv4).also {
                bridge.builder.addAddress(it, 32)
            }

            if (doUseCustomDNSServer) {
                bridge.builder.addDnsServer(getStringPrefValue(OscPrefKey.DNS_CUSTOM_ADDRESS, bridge.prefs))
            }

            if (!bridge.currentProposedDNS.contentEquals(ByteArray(4))) {
                InetAddress.getByAddress(bridge.currentProposedDNS).also {
                    bridge.builder.addDnsServer(it)
                }
            }

            setIPv4BasedRouting()
        }

        if (bridge.PPP_IPv6_ENABLED) {
            if (bridge.currentIPv6.contentEquals(ByteArray(8))) {
                bridge.controlMailbox.send(ControlMessage(Where.IPv6, Result.ERR_INVALID_ADDRESS))
                return
            }

            ByteArray(16).also { // for link local addresses
                "FE80".toHexByteArray().copyInto(it)
                ByteArray(6).copyInto(it, destinationOffset = 2)
                bridge.currentIPv6.copyInto(it, destinationOffset = 8)
                bridge.builder.addAddress(InetAddress.getByAddress(it), 64)
            }

            setIPv6BasedRouting()
        }

        if (doAddCustomRoutes) {
            addCustomRoutes()
        }

        bridge.builder.setMtu(bridge.PPP_MTU)
        bridge.builder.setBlocking(false)
        establish()

        bridge.controlMailbox.send(ControlMessage(Where.IP, Result.PROCEEDED))
    }

    private fun setIPv4BasedRouting() {
        if (doAddDefaultRoute) {
            bridge.builder.addRoute("0.0.0.0", 0)
        }

        if (doRoutePrivateAddresses) {
            bridge.builder.addRoute("10.0.0.0", 8)
            bridge.builder.addRoute("172.16.0.0", 12)
            bridge.builder.addRoute("192.168.0.0", 16)
        }
    }

    private fun setIPv6BasedRouting() {
        if (doAddDefaultRoute) {
            bridge.builder.addRoute("::", 0)
        }

        if (doRoutePrivateAddresses) {
            bridge.builder.addRoute("fc00::", 7)
        }
    }

    private suspend fun addCustomRoutes(): Boolean {
        getStringPrefValue(OscPrefKey.ROUTE_CUSTOM_ROUTES, bridge.prefs).split("\n").filter { it.isNotEmpty() }.forEach {
            val parsed = it.split("/")
            if (parsed.size != 2) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }

            val address = parsed[0]
            val prefix = parsed[1].toIntOrNull()
            if (prefix == null){
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }

            try {
                bridge.builder.addRoute(address, prefix)
            } catch (_: IllegalArgumentException) {
                bridge.controlMailbox.send(ControlMessage(Where.ROUTE, Result.ERR_PARSING_FAILED))
                return false
            }
        }

        return true
    }

    @Synchronized
    private fun establish() {
        check(!closed)
        fd = bridge.builder.establish() ?: error("Android could not establish the SSTP tunnel")
    }

    @Synchronized
    internal fun writePacket(start: Int, size: Int, buffer: ByteBuffer) {
        val descriptor = fd?.fileDescriptor ?: return
        try {
            val written = Os.write(descriptor, buffer.array(), start, size)
            bridge.service.countReceived(bridge.token, written)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EAGAIN) throw error
        }
    }

    @Synchronized
    internal fun readPacket(buffer: ByteBuffer): Boolean {
        val descriptor = fd?.fileDescriptor ?: return false
        val count = try {
            Os.read(descriptor, buffer.array(), 0, bridge.PPP_MTU)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EAGAIN) throw error
            return false
        }
        if (count <= 0) return false
        buffer.clear()
        buffer.limit(count)
        bridge.service.countSent(bridge.token, count)
        return true
    }

    @Synchronized
    internal fun close() {
        closed = true
        val descriptor = fd
        fd = null
        descriptor?.close()
    }
}
