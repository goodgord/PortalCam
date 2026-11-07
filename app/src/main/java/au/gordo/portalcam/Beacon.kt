package au.gordo.portalcam

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Lets the PC find the Portal without any typing. Once a second a small JSON line is broadcast on UDP [port] to
 * every network the Portal is on. The same socket also answers a unicast "PORTALCAM?" probe with the same line,
 * for networks that drop broadcasts.
 */
class Beacon(private val port: Int, private val videoPort: Int, private val speakerPort: Int, private val name: String) {
    private val tag = "PortalCam.Beacon"
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start() {
        running = true
        val s = DatagramSocket(port).also { it.broadcast = true; it.soTimeout = 1000 }
        socket = s
        thread(name = "beacon", isDaemon = true) {
            val buf = ByteArray(256)
            var lastSend = 0L
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastSend >= 1000) {
                    lastSend = now
                    for ((address, broadcast) in interfaces()) {
                        val msg = message(address).toByteArray()
                        try { s.send(DatagramPacket(msg, msg.size, broadcast, port)) } catch (e: Exception) { Log.d(tag, "send: $e") }
                    }
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val text = String(p.data, 0, p.length).trim()
                    if (text.startsWith("PORTALCAM?")) {
                        val reply = message(interfaces().firstOrNull()?.first ?: "").toByteArray()
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) { if (running) Log.d(tag, "recv: $e") }
            }
        }
    }

    fun stop() {
        running = false
        socket?.close(); socket = null
    }

    private fun message(ip: String): String =
        """{"service":"portalcam","name":"$name","ip":"$ip","video":$videoPort,"speaker":$speakerPort,"version":"${BuildConfig.VERSION_NAME}"}"""

    /** (address, broadcast address) for each IPv4 interface that has one. */
    private fun interfaces(): List<Pair<String, InetAddress>> {
        val out = mutableListOf<Pair<String, InetAddress>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    val bcast = ia.broadcast ?: continue
                    out += (addr.hostAddress ?: continue) to bcast
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
