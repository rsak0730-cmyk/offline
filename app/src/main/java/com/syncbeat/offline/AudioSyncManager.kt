package com.syncbeat.offline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class AudioSyncManager(private val context: Context) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "com.syncbeat.offline.P2P_AUDIO"
    
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d("AudioSync", "Connected to $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("AudioSync", "Disconnected from $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection("Listener", endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {}
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // Upgraded to include success and failure tracking
    fun startHosting(userName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startAdvertising(userName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e) }
    }

    // Upgraded to include success and failure tracking
    fun startDiscovering(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e) }
    }

    fun sendAudioFile(endpointId: String, fileUri: Uri) {
        val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
        pfd?.let {
            val filePayload = Payload.fromFile(it)
            connectionsClient.sendPayload(endpointId, filePayload)
        }
    }
}
