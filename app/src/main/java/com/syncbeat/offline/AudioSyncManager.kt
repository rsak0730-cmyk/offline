package com.syncbeat.offline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class AudioSyncManager(private val context: Context) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "com.syncbeat.offline.P2P_AUDIO"
    
    // Callback when someone tries to connect
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept the connection for the music group
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

    // Callback for finding the host
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Request to connect as soon as host is found
            connectionsClient.requestConnection("Listener", endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    // Callback for receiving the MP3 file or Sync Ticks
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.FILE) {
                // An audio file was received, save it locally (Implementation logic goes here)
                Log.d("AudioSync", "Receiving audio file...")
            } else if (payload.type == Payload.Type.BYTES) {
                // A timestamp tick was received, adjust the player (Implementation logic goes here)
                val command = String(payload.asBytes()!!)
                Log.d("AudioSync", "Command received: $command")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun startHosting(userName: String) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startAdvertising(userName, serviceId, connectionLifecycleCallback, options)
    }

    fun startDiscovering() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
    }

    fun sendAudioFile(endpointId: String, fileUri: Uri) {
        val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
        pfd?.let {
            val filePayload = Payload.fromFile(it)
            connectionsClient.sendPayload(endpointId, filePayload)
        }
    }
}

