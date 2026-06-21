package com.popoto.discover.android.core

import android.content.Context
import android.content.SharedPreferences
import com.popoto.core.apple.AppleCoreFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val discoveryPort = 33333
private const val broadcastAddress = "255.255.255.255"
private const val developmentDefaultSecret = "5a21884927730b6388938cd1d2f2937e1e801dae2e4c4561f951fd3974be9de8"

data class SessionLog(
    val message: String,
    val level: String,
    val timestampEpochMillis: Long,
)

data class OutboundPacket(
    val nonce: String,
    val payloadJson: String,
    val timeoutMillis: Long,
)

data class CompletedOperation(
    val nonce: String,
    val type: String,
    val success: Boolean,
    val message: String,
    val rtc: String? = null,
    val rediscoverDelayMillis: Long? = null,
)

data class HydrophoneDevice(
    val key: String,
    val localId: String,
    val name: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val ipAddress: String? = null,
    val macAddress: String? = null,
    val interfaceName: String? = null,
    val configuredMode: String? = null,
    val activeMode: String? = null,
    val linkState: String? = null,
    val topologyHint: String? = null,
    val gatewayReachable: Boolean? = null,
    val netmask: String? = null,
    val gateway: String? = null,
    val firmwareVersion: String? = null,
    val batteryVoltage: Double? = null,
    val sampleRate: Int? = null,
    val rtc: String? = null,
    val storageUsedGb: Double? = null,
    val storageTotalGb: Double? = null,
    val recordingState: String? = null,
    val lastSeenEpochMillis: Long = 0L,
    val rtcQueryEpochMillis: Long? = null,
)

data class SessionSnapshot(
    val status: String,
    val isDiscovering: Boolean,
    val selectedDeviceKey: String?,
    val devicesByKey: Map<String, HydrophoneDevice>,
    val sortedDeviceKeys: List<String>,
    val logs: List<SessionLog>,
)

data class SessionMutation(
    val snapshot: SessionSnapshot,
    val outboundPackets: List<OutboundPacket>,
    val completedOperations: List<CompletedOperation>,
)

class PreferenceSecretStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("popoto_discover", Context.MODE_PRIVATE)

    fun currentSecret(): String? =
        prefs.getString("secret_key", null)?.takeIf { it.isNotBlank() } ?: developmentDefaultSecret

    fun saveSecret(secret: String) {
        if (secret.isBlank()) {
            prefs.edit().remove("secret_key").apply()
        } else {
            prefs.edit().putString("secret_key", secret).apply()
        }
    }
}

class DiscoveryController(
    private val context: Context,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val udpTransport = UdpTransport()
    private val facade = AndroidCoreFacade()
    private val secretStore = PreferenceSecretStore(context)

    private val timeoutJobs = ConcurrentHashMap<String, Job>()
    private val _state = MutableStateFlow(
        SessionSnapshot(
            status = "disconnected",
            isDiscovering = false,
            selectedDeviceKey = null,
            devicesByKey = emptyMap(),
            sortedDeviceKeys = emptyList(),
            logs = emptyList(),
        )
    )
    val state: StateFlow<SessionSnapshot> = _state

    init {
        appScope.launch {
            initializeSession()
        }
    }

    fun currentSecret(): String? = secretStore.currentSecret()

    fun saveSecret(secret: String) {
        secretStore.saveSecret(secret)
    }

    suspend fun discover() {
        performMutation {
            facade.startDiscovery(
                timeoutMillis = 5_000,
                secret = currentSecret(),
                nowEpochMillis = nowEpochMillis(),
            )
        }
    }

    suspend fun selectDevice(uniqueKey: String?) {
        performMutation {
            facade.selectDevice(uniqueKey)
        }
    }

    suspend fun clearLogs() {
        performMutation {
            facade.clearLogs()
        }
    }

    suspend fun setIp(newIp: String, netmask: String, gateway: String) {
        performMutation {
            facade.startSetIp(
                newIp = newIp,
                netmask = netmask,
                gateway = gateway,
                timeoutMillis = 5_000,
                secret = currentSecret(),
                nowEpochMillis = nowEpochMillis(),
            )
        }
    }

    suspend fun setRtc(rtc: String) {
        performMutation {
            facade.startSetRtc(
                rtc = rtc,
                timeoutMillis = 5_000,
                secret = currentSecret(),
                nowEpochMillis = nowEpochMillis(),
            )
        }
    }

    suspend fun getRtc() {
        performMutation {
            facade.startGetRtc(
                timeoutMillis = 5_000,
                secret = currentSecret(),
                nowEpochMillis = nowEpochMillis(),
            )
        }
    }

    suspend fun setParam(paramName: String, paramValue: String) {
        performMutation {
            facade.startSetParamString(
                paramName = paramName,
                paramValue = paramValue,
                timeoutMillis = 5_000,
                secret = currentSecret(),
                nowEpochMillis = nowEpochMillis(),
            )
        }
    }

    suspend fun initializeSession() {
        mutex.withLock {
            if (udpTransport.isRunning) {
                return@withLock
            }

            udpTransport.start { payload -> onIncomingPacket(payload) }

            facade.initializeSession(nowEpochMillis = nowEpochMillis())?.let { applyMutation(it) }
        }
    }

    suspend fun shutdown() {
        mutex.withLock {
            udpTransport.stop()
            timeoutJobs.values.forEach { it.cancel() }
            timeoutJobs.clear()
        }
        appScope.cancel()
    }

    private suspend fun performMutation(mutationProvider: suspend () -> SessionMutation?) {
        val mutation = mutex.withLock {
            mutationProvider()
        } ?: return

        applyMutation(mutation)
    }

    private suspend fun onIncomingPacket(payload: String) {
        val mutationJson = mutex.withLock {
            facade.handleIncomingPacket(
                json = payload,
                secret = currentSecret(),
                receivedAtEpochMillis = nowEpochMillis(),
            )
        }

        mutationJson?.let { json ->
            try {
                applyMutation(parseSessionMutation(json) ?: return)
            } catch (_: Exception) {
                // Ignore malformed packets to keep receiver resilient.
            }
        }
    }

    private suspend fun applyMutation(mutation: SessionMutation) {
        _state.value = mutation.snapshot

        mutation.outboundPackets.forEach { packet ->
            val payload = packet.payloadJson.toByteArray(Charsets.UTF_8)
            udpTransport.send(payload)
            scheduleTimeout(packet)
        }

        mutation.completedOperations.forEach { operation ->
            if (operation.nonce.isNotEmpty()) {
                timeoutJobs.remove(operation.nonce)?.cancel()
            }

            if (operation.success &&
                operation.type == "set_ip" &&
                operation.rediscoverDelayMillis != null
            ) {
                val delay = operation.rediscoverDelayMillis
                appScope.launch {
                    delay(delay)
                    discover()
                }
            }
        }
    }

    private fun scheduleTimeout(packet: OutboundPacket) {
        if (packet.nonce.isBlank()) return

        timeoutJobs[packet.nonce]?.cancel()

        timeoutJobs[packet.nonce] = appScope.launch {
            try {
                delay(packet.timeoutMillis)
                val timeoutMutation = mutex.withLock {
                    facade.handleTimeout(
                        nonce = packet.nonce,
                        nowEpochMillis = nowEpochMillis(),
                    )
                }

                timeoutMutation?.let {
                    applyMutation(it)
                }
            } catch (_: CancellationException) {
                // ignored
            }
        }
    }
}

private class UdpTransport {
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    var isRunning: Boolean = false
        private set

    private val receiveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(onPacket: suspend (String) -> Unit) {
        if (isRunning) return

        val localSocket = DatagramSocket(null)
        localSocket.reuseAddress = true
        localSocket.broadcast = true
        localSocket.bind(InetSocketAddress(discoveryPort))
        localSocket.soTimeout = 250

        socket = localSocket
        isRunning = true

        receiveJob = receiveScope.launch {
            val buffer = ByteArray(65_535)

            while (isRunning) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    localSocket.receive(packet)
                    val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (payload.isNotBlank()) {
                        onPacket(payload)
                    }
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: SocketException) {
                    if (!isRunning) {
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
    }

    suspend fun send(payload: ByteArray) {
        val localSocket = socket ?: return
        if (!isRunning) return

        withContext(Dispatchers.IO) {
            resolveBroadcastTargets().forEach { address ->
                runCatching {
                    val packet = DatagramPacket(payload, payload.size, address, discoveryPort)
                    localSocket.send(packet)
                }
            }
        }
    }

    private fun resolveBroadcastTargets(): List<Inet4Address> {
        val targets = linkedSetOf<Inet4Address>()
        (InetAddress.getByName(broadcastAddress) as? Inet4Address)?.let(targets::add)

        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())

        interfaces
            .asSequence()
            .filter { networkInterface ->
                runCatching {
                    networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isPointToPoint
                }.getOrDefault(false)
            }
            .flatMap { networkInterface -> networkInterface.interfaceAddresses.asSequence() }
            .mapNotNull { interfaceAddress -> interfaceAddress.broadcast as? Inet4Address }
            .forEach(targets::add)

        return targets.toList()
    }
}

private class AndroidCoreFacade {
    private val facade = AppleCoreFacade()

    fun initializeSession(nowEpochMillis: Long): SessionMutation? {
        return parseSessionMutation(facade.initializeSessionJson(nowEpochMillis))
    }

    fun selectDevice(uniqueKey: String?): SessionMutation? {
        return parseSessionMutation(facade.selectDeviceJson(uniqueKey))
    }

    fun clearLogs(): SessionMutation? {
        return parseSessionMutation(facade.clearLogsJson())
    }

    fun startDiscovery(timeoutMillis: Long, secret: String?, nowEpochMillis: Long): SessionMutation? {
        return parseSessionMutation(
            facade.startDiscoveryJson(
                timeoutMillis = timeoutMillis,
                secret = secret,
                nowEpochMillis = nowEpochMillis,
            )
        )
    }

    fun startSetIp(
        newIp: String,
        netmask: String,
        gateway: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation? {
        return parseSessionMutation(
            facade.startSetIpForSelectedDeviceJson(
                newIp = newIp,
                netmask = netmask,
                gateway = gateway,
                timeoutMillis = timeoutMillis,
                secret = secret,
                nowEpochMillis = nowEpochMillis,
            )
        )
    }

    fun startSetRtc(
        rtc: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation? {
        return parseSessionMutation(
            facade.startSetRtcForSelectedDeviceJson(
                rtc = rtc,
                timeoutMillis = timeoutMillis,
                secret = secret,
                nowEpochMillis = nowEpochMillis,
            )
        )
    }

    fun startGetRtc(
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation? {
        return parseSessionMutation(
            facade.startGetRtcForSelectedDeviceJson(
                timeoutMillis = timeoutMillis,
                secret = secret,
                nowEpochMillis = nowEpochMillis,
            )
        )
    }

    fun startSetParamString(
        paramName: String,
        paramValue: String,
        timeoutMillis: Long,
        secret: String?,
        nowEpochMillis: Long,
    ): SessionMutation? {
        return parseSessionMutation(
            facade.startSetParamStringForSelectedDeviceJson(
                paramName = paramName,
                paramValue = paramValue,
                timeoutMillis = timeoutMillis,
                secret = secret,
                nowEpochMillis = nowEpochMillis,
            )
        )
    }

    fun handleIncomingPacket(json: String, secret: String?, receivedAtEpochMillis: Long): String? {
        return facade.handleIncomingPacketJson(
            json = json,
            secret = secret,
            receivedAtEpochMillis = receivedAtEpochMillis,
        )
    }

    fun handleTimeout(nonce: String, nowEpochMillis: Long): SessionMutation? {
        return parseSessionMutation(
            facade.handleTimeoutJson(
                nonce = nonce,
                nowEpochMillis = nowEpochMillis,
            )
        )
    }
}

private fun parseSessionMutation(json: String?): SessionMutation? {
    if (json == null) {
        return null
    }

    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val snapshot = runCatching {
        parseSessionSnapshot(root.optJSONObject("snapshot"))
    }.getOrNull() ?: return null
    val outboundPackets = parseOutboundPackets(root.optJSONArray("outboundPackets"))
    val completedOperations = parseCompletedOperations(root.optJSONArray("completedOperations"))

    return SessionMutation(
        snapshot = snapshot,
        outboundPackets = outboundPackets,
        completedOperations = completedOperations,
    )
}

private fun parseSessionSnapshot(json: JSONObject?): SessionSnapshot {
    if (json == null) {
        return SessionSnapshot(
            status = "disconnected",
            isDiscovering = false,
            selectedDeviceKey = null,
            devicesByKey = emptyMap(),
            sortedDeviceKeys = emptyList(),
            logs = emptyList(),
        )
    }

    val devicesByKey = mutableMapOf<String, HydrophoneDevice>()
    val devicesObj = json.optJSONObject("devicesByKey") ?: JSONObject()

    val keysIterator = devicesObj.keys()
    while (keysIterator.hasNext()) {
        val key = keysIterator.next()
        val payload = devicesObj.optJSONObject(key) ?: JSONObject()
        devicesByKey[key] = parseDevice(key = key, payload)
    }

    val logsArray = json.optJSONArray("logs") ?: JSONArray()
    val logs = mutableListOf<SessionLog>()

    for (i in 0 until logsArray.length()) {
        val logPayload = logsArray.optJSONObject(i) ?: continue
        logs.add(
            SessionLog(
                message = logPayload.optString("message", ""),
                level = logPayload.optString("level", "info"),
                timestampEpochMillis = (logPayload.opt("timestampEpochMillis") as? Number)?.toLong()
                    ?: 0L,
            )
        )
    }

    return SessionSnapshot(
        status = json.optString("status", "disconnected"),
        isDiscovering = json.optBoolean("isDiscovering", false),
        selectedDeviceKey = json.optString("selectedDeviceKey").takeIf { it.isNotBlank() },
        devicesByKey = devicesByKey,
        sortedDeviceKeys = parseStringList(json.optJSONArray("sortedDeviceKeys")),
        logs = logs,
    )
}

private fun parseDevice(key: String, payload: JSONObject): HydrophoneDevice {
    return HydrophoneDevice(
        key = key,
        localId = payload.optString("localId", "android-$key"),
        name = payload.optString("name").ifBlank { null },
        model = payload.optString("model").ifBlank { null },
        serial = payload.optString("serial").ifBlank { null },
        ipAddress = payload.optString("ipAddress").ifBlank { null },
        macAddress = payload.optString("macAddress").ifBlank { null },
        interfaceName = payload.optString("interfaceName").ifBlank { null },
        configuredMode = payload.optString("configuredMode").ifBlank { null },
        activeMode = payload.optString("activeMode").ifBlank { null },
        linkState = payload.optString("linkState").ifBlank { null },
        topologyHint = payload.optString("topologyHint").ifBlank { null },
        gatewayReachable = payload.opt("gatewayReachable") as? Boolean,
        netmask = payload.optString("netmask").ifBlank { null },
        gateway = payload.optString("gateway").ifBlank { null },
        firmwareVersion = payload.optString("firmwareVersion").ifBlank { null },
        batteryVoltage = (payload.opt("batteryVoltage") as? Number)?.toDouble(),
        sampleRate = (payload.opt("sampleRate") as? Number)?.toInt(),
        rtc = payload.optString("rtc").ifBlank { null },
        storageUsedGb = (payload.opt("storageUsedGb") as? Number)?.toDouble(),
        storageTotalGb = (payload.opt("storageTotalGb") as? Number)?.toDouble(),
        recordingState = payload.optString("recordingState").ifBlank { null },
        lastSeenEpochMillis = (payload.opt("lastSeenEpochMillis") as? Number)?.toLong() ?: 0L,
        rtcQueryEpochMillis = (payload.opt("rtcQueryEpochMillis") as? Number)?.toLong(),
    )
}

private fun parseOutboundPackets(array: JSONArray?): List<OutboundPacket> {
    val packets = mutableListOf<OutboundPacket>()
    if (array == null) return emptyList()

    for (i in 0 until array.length()) {
        val payload = array.optJSONObject(i) ?: continue
        packets.add(
            OutboundPacket(
                nonce = payload.optString("nonce", ""),
                payloadJson = payload.optString("payloadJson", ""),
                timeoutMillis = (payload.opt("timeoutMillis") as? Number)?.toLong() ?: 0L,
            )
        )
    }

    return packets
}

private fun parseCompletedOperations(array: JSONArray?): List<CompletedOperation> {
    val operations = mutableListOf<CompletedOperation>()
    if (array == null) return emptyList()

    for (i in 0 until array.length()) {
        val payload = array.optJSONObject(i) ?: continue
        operations.add(
            CompletedOperation(
                nonce = payload.optString("nonce", ""),
                type = payload.optString("type", ""),
                success = payload.optBoolean("success", false),
                message = payload.optString("message", ""),
                rtc = payload.optString("rtc").ifBlank { null },
                rediscoverDelayMillis = (payload.opt("rediscoverDelayMillis") as? Number)?.toLong(),
            )
        )
    }

    return operations
}

private fun parseStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val values = mutableListOf<String>()

    for (i in 0 until array.length()) {
        val value = array.optString(i)
        if (value.isNotBlank()) {
            values.add(value)
        }
    }

    return values
}

private fun nowEpochMillis(): Long = System.currentTimeMillis()
