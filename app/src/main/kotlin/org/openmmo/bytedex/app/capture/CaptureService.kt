package org.openmmo.bytedex.app.capture

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openmmo.bytedex.app.Config
import org.openmmo.bytedex.app.auth.TokenStore
import org.openmmo.bytedex.proxy.netty.PacketSink
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

class CaptureService(
    private val tokenStore: TokenStore,
    private val baseUrl: String = Config.apiBaseUrl,
    private val blacklist: PacketBlacklist = PacketBlacklist.load(),
) : PacketSink {

    private val log = LoggerFactory.getLogger("bytedex.capture")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }

    private val sessionId = AtomicReference<String?>(null)
    private val queue = Channel<WireSubmission>(capacity = Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var uploaderJob: Job? = null
    @Volatile private var statsJob: Job? = null

    private val totalPackets = AtomicLong(0)
    private val totalBytes = AtomicLong(0)
    private val perProtocol = ConcurrentHashMap<PacketSink.Protocol, AtomicLong>()
    private val rateWindow = ArrayDeque<Long>()
    @Volatile private var sessionStartedAtMs: Long = 0L
    @Volatile private var sessionGameVersion: Long = 0L
    private val statsFlow = MutableStateFlow(CaptureStats.empty())
    val stats: StateFlow<CaptureStats> = statsFlow.asStateFlow()

    private val packetSeq = AtomicLong(0)
    private val recentBuffer = ArrayDeque<RecordedPacket>(RECENT_BUFFER_CAP)
    private val recentFlow = MutableStateFlow<List<RecordedPacket>>(emptyList())
    val recentPackets: StateFlow<List<RecordedPacket>> = recentFlow.asStateFlow()

    suspend fun open(gameVersion: Long, clientInfo: String? = null): Result<String> = runCatching {
        close()
        val res = authed { token ->
            http.post("$baseUrl/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(SessionCreateBody(gameVersion = gameVersion, clientInfo = clientInfo))
            }
        }
        if (!res.status.isSuccess()) error("create session failed: ${res.status}")
        val created: SessionCreated = res.body()
        sessionId.set(created.id)
        resetStats(gameVersion, created.id)
        uploaderJob = scope.launch { drainLoop() }
        statsJob = scope.launch { statsLoop() }
        log.info("opened bytedex session {} (gameVersion={})", created.id, gameVersion)
        created.id
    }

    private fun resetStats(gameVersion: Long, newSessionId: String) {
        totalPackets.set(0)
        totalBytes.set(0)
        perProtocol.clear()
        synchronized(rateWindow) { rateWindow.clear() }
        synchronized(recentBuffer) { recentBuffer.clear() }
        recentFlow.value = emptyList()
        sessionGameVersion = gameVersion
        sessionStartedAtMs = System.currentTimeMillis()
        statsFlow.value = CaptureStats(
            sessionId = newSessionId,
            gameVersion = gameVersion,
            startedAtEpochMs = sessionStartedAtMs,
            totalPackets = 0,
            totalBytes = 0,
            packetsPerSecond = 0,
            perProtocol = emptyMap(),
        )
    }

    private suspend fun statsLoop() {
        while (true) {
            delay(STATS_TICK_MS.milliseconds)
            statsFlow.value = snapshotStats()
            recentFlow.value = synchronized(recentBuffer) { recentBuffer.toList() }
        }
    }

    private fun snapshotStats(): CaptureStats {
        val now = System.currentTimeMillis()
        val cutoff = now - RATE_WINDOW_MS
        synchronized(rateWindow) {
            while (rateWindow.isNotEmpty() && rateWindow.first() < cutoff) {
                rateWindow.removeFirst()
            }
        }
        return CaptureStats(
            sessionId = sessionId.get(),
            gameVersion = sessionGameVersion,
            startedAtEpochMs = sessionStartedAtMs,
            totalPackets = totalPackets.get(),
            totalBytes = totalBytes.get(),
            packetsPerSecond = synchronized(rateWindow) { rateWindow.size },
            perProtocol = perProtocol.mapValues { it.value.get() }.toMap(),
        )
    }

    suspend fun close() {
        val id = sessionId.getAndSet(null) ?: return
        uploaderJob?.cancel()
        uploaderJob = null
        statsJob?.cancel()
        statsJob = null
        statsFlow.value = CaptureStats.empty()
        synchronized(recentBuffer) { recentBuffer.clear() }
        recentFlow.value = emptyList()
        runCatching {
            authed { token ->
                http.post("$baseUrl/sessions/$id/close") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
        }.onFailure { log.warn("close session {} failed: {}", id, it.toString()) }
        log.info("closed bytedex session {}", id)
    }

    override fun accept(
        protocol: PacketSink.Protocol,
        direction: PacketSink.Direction,
        packetId: Int,
        payload: ByteArray,
        capturedAtEpochMillis: Long,
    ) {
        if (sessionId.get() == null) return
        if (blacklist.isBlocked(protocol, direction, packetId)) return
        totalPackets.incrementAndGet()
        totalBytes.addAndGet(payload.size.toLong())
        perProtocol.computeIfAbsent(protocol) { AtomicLong(0) }.incrementAndGet()
        val record = RecordedPacket(
            seq = packetSeq.incrementAndGet(),
            protocol = protocol,
            direction = direction,
            packetId = packetId,
            size = payload.size,
            capturedAtEpochMs = capturedAtEpochMillis,
            payload = payload,
        )
        synchronized(recentBuffer) {
            recentBuffer.addFirst(record)
            while (recentBuffer.size > RECENT_BUFFER_CAP) recentBuffer.removeLast()
        }
        synchronized(rateWindow) {
            val cutoff = capturedAtEpochMillis - RATE_WINDOW_MS
            while (rateWindow.isNotEmpty() && rateWindow.first() < cutoff) {
                rateWindow.removeFirst()
            }
            rateWindow.addLast(capturedAtEpochMillis)
        }
        val submission = WireSubmission(
            protocol = protocol.name,
            direction = direction.name,
            packetId = packetId,
            payload = Base64.getEncoder().encodeToString(payload),
            capturedAt = Instant.ofEpochMilli(capturedAtEpochMillis).toString(),
        )
        queue.trySend(submission)
    }

    private suspend fun drainLoop() {
        val batch = ArrayList<WireSubmission>(MAX_BATCH_SIZE)
        while (true) {
            batch.clear()
            batch += queue.receive()
            val deadline = System.currentTimeMillis() + MAX_BATCH_LATENCY_MS
            while (batch.size < MAX_BATCH_SIZE) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val next = withTimeoutOrNull(remaining.milliseconds) { queue.receive() } ?: break
                batch += next
            }
            ship(batch.toList())
        }
    }

    private suspend fun ship(packets: List<WireSubmission>) {
        val id = sessionId.get() ?: return
        try {
            val res = authed { token ->
                http.post("$baseUrl/sessions/$id/packets") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(WireBatch(packets))
                }
            }
            if (!res.status.isSuccess()) {
                log.warn("submit batch ({} packets) -> {}", packets.size, res.status)
            } else {
                log.debug("submitted {} packets ({})", packets.size, res.status)
            }
        } catch (t: Throwable) {
            log.warn("submit batch failed: {}", t.toString())
            delay(1_000.milliseconds)
        }
    }

    private suspend fun authed(block: suspend (String) -> io.ktor.client.statement.HttpResponse): io.ktor.client.statement.HttpResponse {
        val token = tokenStore.load()?.accessToken ?: error("not logged in")
        val first = block(token)
        if (first.status != HttpStatusCode.Unauthorized) return first
        val refreshed = refreshOrThrow()
        return block(refreshed)
    }

    private suspend fun refreshOrThrow(): String {
        val current = tokenStore.load() ?: error("not logged in")
        val res = http.post("$baseUrl/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(current.refreshToken))
        }
        if (!res.status.isSuccess()) {
            tokenStore.clear()
            error("refresh failed: ${res.status}")
        }
        val t: TokenResponse = res.body()
        tokenStore.save(
            org.openmmo.bytedex.app.auth.Tokens(
                accessToken = t.accessToken,
                refreshToken = t.refreshToken,
                expiresAt = System.currentTimeMillis() + t.expiresIn * 1_000L,
            ),
        )
        return t.accessToken
    }

    fun shutdown() {
        runCatching { scope.cancel() }
        runCatching { http.close() }
    }

    @Serializable
    private data class SessionCreateBody(
        val gameVersion: Long,
        val clientInfo: String? = null,
    )

    @Serializable
    private data class SessionCreated(val id: String)

    @Serializable
    private data class WireSubmission(
        val protocol: String,
        val direction: String,
        val packetId: Int,
        val payload: String,
        val capturedAt: String,
    )

    @Serializable
    private data class WireBatch(val packets: List<WireSubmission>)

    @Serializable
    private data class RefreshRequest(val refreshToken: String)

    @Serializable
    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresIn: Int,
    )

    companion object {
        private const val MAX_BATCH_SIZE = 256
        private const val MAX_BATCH_LATENCY_MS = 500L
        private const val RATE_WINDOW_MS = 1_000L
        private const val STATS_TICK_MS = 250L
        private const val RECENT_BUFFER_CAP = 2_000
    }
}

data class RecordedPacket(
    val seq: Long,
    val protocol: PacketSink.Protocol,
    val direction: PacketSink.Direction,
    val packetId: Int,
    val size: Int,
    val capturedAtEpochMs: Long,
    val payload: ByteArray,
)

data class CaptureStats(
    val sessionId: String?,
    val gameVersion: Long,
    val startedAtEpochMs: Long,
    val totalPackets: Long,
    val totalBytes: Long,
    val packetsPerSecond: Int,
    val perProtocol: Map<PacketSink.Protocol, Long>,
) {
    companion object {
        fun empty() = CaptureStats(
            sessionId = null,
            gameVersion = 0L,
            startedAtEpochMs = 0L,
            totalPackets = 0L,
            totalBytes = 0L,
            packetsPerSecond = 0,
            perProtocol = emptyMap(),
        )
    }
}
