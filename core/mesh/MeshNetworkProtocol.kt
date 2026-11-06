package com.mobiletracker.core.mesh

import java.io.Serializable

data class PeerNode(
    val peerId: String,
    val networkAddress: String,
    val isInternetBridge: Boolean,
    val lastSeenTimestamp: Long
)

data class SyncPacket(
    val packetId: String,
    val originDeviceId: String,
    val vectorClock: Map<String, Long>, // Distributed CRDT clock
    val encryptedTelemetryBatches: List<ByteArray>,
    val encryptedNotificationEvents: List<ByteArray>,
    val requiresInternetBridge: Boolean
) : Serializable

interface MeshNetworkTransport {
    fun broadcastToPeers(packet: SyncPacket)
    fun sendDirect(targetPeerId: String, packet: SyncPacket)
}

/**
 * P2P Mesh Protocol Engine & Dynamic Bridge Coordinator
 * Manages ad-hoc multi-hop state distribution and dynamic uplink gateway election.
 */
class MeshProtocolEngine(
    val localDeviceId: String,
    private val transport: MeshNetworkTransport
) {
    private val vectorClock = mutableMapOf<String, Long>()
    private val discoveredPeers = mutableMapOf<String, PeerNode>()
    private var isCloudBackhaulAvailable = false

    fun updateBackhaulStatus(isAvailable: Boolean) {
        this.isCloudBackhaulAvailable = isAvailable
    }

    fun registerPeer(peer: PeerNode) {
        discoveredPeers[peer.peerId] = peer
    }

    fun removePeer(peerId: String) {
        discoveredPeers.remove(peerId)
    }

    /**
     * Increments local vector clock and broadcasts local state change.
     */
    fun publishLocalUpdate(
        encryptedTelemetry: List<ByteArray>,
        encryptedNotifications: List<ByteArray>
    ) {
        val currentSeq = vectorClock.getOrDefault(localDeviceId, 0L) + 1L
        vectorClock[localDeviceId] = currentSeq

        val packet = SyncPacket(
            packetId = "$localDeviceId-$currentSeq",
            originDeviceId = localDeviceId,
            vectorClock = HashMap(vectorClock),
            encryptedTelemetryBatches = encryptedTelemetry,
            encryptedNotificationEvents = encryptedNotifications,
            requiresInternetBridge = !isCloudBackhaulAvailable
        )

        transport.broadcastToPeers(packet)
    }

    /**
     * Handles received sync packet from Wi-Fi Direct or BLE mesh channel.
     * Implements Dynamic Bridge routing: if this node has backhaul, relays to cloud.
     */
    fun onPacketReceived(packet: SyncPacket, onRelayToCloud: (SyncPacket) -> Unit) {
        // Causality check via Vector Clock
        val remoteSeq = packet.vectorClock[packet.originDeviceId] ?: 0L
        val localKnownSeq = vectorClock.getOrDefault(packet.originDeviceId, 0L)

        if (remoteSeq > localKnownSeq) {
            // New state update
            vectorClock[packet.originDeviceId] = remoteSeq

            // Dynamic Bridge Election:
            // If this node possesses active cellular/Wi-Fi backhaul and the peer's packet needs bridge,
            // immediately uplink to cloud API gateway!
            if (isCloudBackhaulAvailable && packet.requiresInternetBridge) {
                onRelayToCloud(packet)
            }
        }
    }
}
