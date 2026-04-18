package com.alertnet.app.db

import com.alertnet.app.model.DeliveryStatus
import com.alertnet.app.model.MeshMessage
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.MessageType
import com.alertnet.app.model.TransportType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

// ─── MeshMessage ↔ MessageEntity ─────────────────────────────────

fun MeshMessage.toEntity(): MessageEntity {
    return MessageEntity(
        id = id,
        senderId = senderId,
        targetId = targetId,
        type = type.name,
        payload = payload,
        fileName = fileName,
        mimeType = mimeType,
        timestamp = timestamp,
        ttl = ttl,
        hopCount = hopCount,
        hopPath = json.encodeToString(hopPath),
        status = status.name,
        ackForMessageId = ackForMessageId
    )
}

fun MessageEntity.toModel(): MeshMessage {
    return MeshMessage(
        id = id,
        senderId = senderId,
        targetId = targetId,
        type = try { MessageType.valueOf(type) } catch (e: Exception) { MessageType.TEXT },
        payload = payload,
        fileName = fileName,
        mimeType = mimeType,
        timestamp = timestamp,
        ttl = ttl,
        hopCount = hopCount,
        hopPath = try { json.decodeFromString<List<String>>(hopPath) } catch (e: Exception) { emptyList() },
        status = try { DeliveryStatus.valueOf(status) } catch (e: Exception) { DeliveryStatus.QUEUED },
        ackForMessageId = ackForMessageId
    )
}

// ─── MeshPeer ↔ PeerEntity ───────────────────────────────────────

fun MeshPeer.toEntity(): PeerEntity {
    return PeerEntity(
        deviceId = deviceId,
        displayName = displayName,
        lastSeen = lastSeen,
        rssi = rssi,
        transportType = transportType.name,
        ipAddress = ipAddress,
        macAddress = macAddress
    )
}

fun PeerEntity.toModel(): MeshPeer {
    return MeshPeer(
        deviceId = deviceId,
        displayName = displayName,
        lastSeen = lastSeen,
        rssi = rssi,
        transportType = try { TransportType.valueOf(transportType) } catch (e: Exception) { TransportType.WIFI_DIRECT },
        ipAddress = ipAddress,
        macAddress = macAddress
    )
}
