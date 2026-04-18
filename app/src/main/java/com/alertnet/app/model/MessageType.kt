package com.alertnet.app.model

import kotlinx.serialization.Serializable

/**
 * Type of mesh message. Each type is handled differently by the router.
 */
@Serializable
enum class MessageType {
    /** Regular text message */
    TEXT,
    /** Image attachment (payload contains base64-encoded image) */
    IMAGE,
    /** File attachment (payload contains base64-encoded file) */
    FILE,
    /** Delivery acknowledgment routed back to sender */
    ACK,
    /** Periodic peer presence announcement */
    PEER_ANNOUNCE,
    /** Peer leaving the mesh gracefully */
    PEER_LEAVE
}
