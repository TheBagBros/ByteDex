package org.openmmo.bytedex.proxy

import org.openmmo.bytedex.proxy.netty.PacketPatcher
import org.openmmo.bytedex.proxy.netty.PacketSink
import org.openmmo.bytedex.proxy.netty.ProxyServer
import org.openmmo.bytedex.proxy.netty.patchers.GamePatcher
import org.openmmo.bytedex.proxy.netty.patchers.LoginPatcher

private const val LOOPBACK = "127.0.0.1"

class Proxy(
    private val sink: PacketSink = PacketSink.NONE,
) {

    private val servers = mutableListOf<ProxyServer>()

    fun start() {
        val chatProxy = ProxyServer(
            name = "chat",
            listenHost = LOOPBACK,
            listenPort = 7778,
            initialUpstreamHost = null,
            signingPrivateKey = ProxyKeys.cs.privateKey,
            signingPublicKey = ProxyKeys.cs.publicKey,
            patcher = PacketPatcher.PASSTHROUGH,
            protocol = PacketSink.Protocol.CHAT,
            sink = sink,
        )

        val gameProxy = ProxyServer(
            name = "game",
            listenHost = LOOPBACK,
            listenPort = 7777,
            initialUpstreamHost = null,
            signingPrivateKey = ProxyKeys.gs.privateKey,
            signingPublicKey = ProxyKeys.gs.publicKey,
            patcher = GamePatcher(
                onChatServerDiscovered = chatProxy::setUpstream,
                targetChatProxyPort = 7778,
            ),
            compressedServerToClient = true,
            protocol = PacketSink.Protocol.GAME,
            sink = sink,
        )

        val loginProxy = ProxyServer(
            name = "login",
            listenHost = LOOPBACK,
            listenPort = 2106,
            initialUpstreamHost = "loginserver.pokemmo.com",
            initialUpstreamPort = 2106,
            signingPrivateKey = ProxyKeys.ls.privateKey,
            signingPublicKey = ProxyKeys.ls.publicKey,
            patcher = LoginPatcher(
                onGameServerDiscovered = gameProxy::setUpstream,
                targetGameProxyPort = 7777,
            ),
            protocol = PacketSink.Protocol.LOGIN,
            sink = sink,
        )

        servers += chatProxy
        servers += gameProxy
        servers += loginProxy
        servers.forEach { it.start() }
    }

    fun stop() {
        servers.forEach { runCatching { it.stop() } }
        servers.clear()
    }
}

fun main() {
    // Lean capture (BROMMO): log every decrypted packet to a file instead of the DB/api sink.
    val logPath = System.getProperty("bytedex.captureLog", "bytedex-capture.log")
    // Opt-in diagnostics, same flag as the agent (-Dbytedex.debug); must be passed to THIS JVM too.
    val debug = System.getProperty("bytedex.debug")
        ?.let { !it.equals("false", ignoreCase = true) && it != "0" } == true
    val firstPacketLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    val out = java.io.File(logPath).bufferedWriter()
    val sink = PacketSink { protocol, direction, packetId, payload, capturedAt ->
        if (debug && firstPacketLogged.compareAndSet(false, true)) {
            System.err.println(
                "[bytedex] capture proxy first packet captured: " +
                    "$protocol $direction id=$packetId len=${payload.size}",
            )
        }
        val hex = buildString(payload.size * 2) { for (b in payload) append("%02x".format(b)) }
        synchronized(out) {
            out.write("$capturedAt $protocol $direction id=$packetId len=${payload.size} $hex")
            out.newLine()
            out.flush()
        }
    }
    val proxy = Proxy(sink = sink)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { synchronized(out) { out.close() } }
            proxy.stop()
        },
    )
    proxy.start()
    println("[bytedex] capture proxy listening on 127.0.0.1:2106/7777/7778 -> logging to $logPath")
    Thread.currentThread().join()
}
