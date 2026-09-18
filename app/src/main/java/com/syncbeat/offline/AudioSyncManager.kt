package com.syncbeat.offline

import android.content.Context
import android.net.Uri
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class AudioSyncManager(private val context: Context) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "com.syncbeat.offline.P2P_AUDIO"
    
    private val connectedEndpoints = mutableListOf<String>()
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()
    
    var onAudioReceived: ((Uri) -> Unit)? = null
    var onSyncTickReceived: ((Long) -> Unit)? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection("Listener", endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.FILE) {
                incomingFilePayloads[payload.id] = payload
            } else if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    val timestamp = String(bytes).toLongOrNull()
                    if (timestamp != null) {
                        onSyncTickReceived?.invoke(timestamp)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val payload = incomingFilePayloads.remove(update.payloadId)
                if (payload != null && payload.type == Payload.Type.FILE) {
                    val uri = payload.asFile()?.asUri() ?: payload.asFile()?.asJavaFile()?.let { Uri.fromFile(it) }
                    uri?.let { onAudioReceived?.invoke(it) }
                }
            }
        }
    }

    fun startHosting(userName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startAdvertising(userName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun startDiscovering(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun stopAllConnections() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
    }

    fun broadcastAudioFile(fileUri: Uri) {
        val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
        pfd?.let {
            val filePayload = Payload.fromFile(it)
            for (endpointId in connectedEndpoints) {
                connectionsClient.sendPayload(endpointId, filePayload)
            }
        }
    }

    fun sendSyncTick(positionMs: Long) {
        val payload = Payload.fromBytes(positionMs.toString().toByteArray())
        for (endpointId in connectedEndpoints) {
            connectionsClient.sendPayload(endpointId, payload)
        }
    }
}
