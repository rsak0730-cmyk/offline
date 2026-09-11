package com.syncbeat.offline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class AudioSyncManager(private val context: Context) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "com.syncbeat.offline.P2P_AUDIO"
    
    // Keeps track of all connected friends
    private val connectedEndpoints = mutableListOf<String>()
    
    // Temporarily holds incoming files while they download
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()
    
    // A trigger to tell the main screen when a song is ready to play
    var onAudioReceived: ((Uri) -> Unit)? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                Log.d("AudioSync", "Connected to $endpointId")
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
                // Save the file ID while it downloads in the background
                incomingFilePayloads[payload.id] = payload
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val payload = incomingFilePayloads.remove(update.payloadId)
                if (payload != null && payload.type == Payload.Type.FILE) {
                    // Download complete! Get the file URI and trigger the music player
                    val uri = payload.asFile()?.asUri()
                    if (uri != null) {
                        onAudioReceived?.invoke(uri)
                    } else {
                        // Fallback for older Android versions
                        payload.asFile()?.asJavaFile()?.let { file ->
                            onAudioReceived?.invoke(Uri.fromFile(file))
                        }
                    }
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

    // Beams the audio file to EVERYONE in the room
    fun broadcastAudioFile(fileUri: Uri) {
        val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
        pfd?.let {
            val filePayload = Payload.fromFile(it)
            for (endpointId in connectedEndpoints) {
                connectionsClient.sendPayload(endpointId, filePayload)
            }
        }
    }
}
