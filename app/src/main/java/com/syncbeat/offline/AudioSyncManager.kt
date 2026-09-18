package com.syncbeat.offline

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlin.math.max

class AudioSyncManager(
    private val player: ExoPlayer,
    private val onTrackChangeRequested: ((String) -> Unit)? = null 
) {
    private var isHost: Boolean = false
    private var timeSynchronizer: TimeSynchronizer? = null
    private var networkManager: NetworkCommandManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // 150ms buffer gives the network time to deliver the UDP packet before execution
    private val NETWORK_BUFFER_MS = 150L 
    private var currentBroadcastIp: String = "255.255.255.255"

    fun initialize(isHost: Boolean, hostIp: String? = null, broadcastIp: String = "255.255.255.255") {
        this.isHost = isHost
        this.currentBroadcastIp = broadcastIp
        timeSynchronizer = TimeSynchronizer(isHost)
        timeSynchronizer?.start()
        
        if (!isHost && hostIp != null) {
            timeSynchronizer?.syncWithHost(hostIp)
        }

        networkManager = NetworkCommandManager(isHost) { action, executeAt, positionMs, trackId ->
            scheduleAction(action, executeAt, positionMs, trackId)
        }
        networkManager?.start()
    }

    fun hostPlay() = triggerAction("PLAY", currentPosition())
    fun hostPause() = triggerAction("PAUSE", currentPosition())
    fun hostSeekTo(positionMs: Long) = triggerAction("SEEK", positionMs)
    fun hostNextTrack(nextTrackUri: String) = triggerAction("CHANGE_TRACK", 0L, nextTrackUri)

    private fun currentPosition(): Long = try { player.currentPosition } catch (e: Exception) { 0L }

    private fun triggerAction(action: String, positionMs: Long, trackId: String? = null) {
        if (!isHost) return
        
        val executeAt = (timeSynchronizer?.getSyncedTime() ?: System.currentTimeMillis()) + NETWORK_BUFFER_MS
        networkManager?.broadcastCommand(currentBroadcastIp, action, executeAt, positionMs, trackId)
        scheduleAction(action, executeAt, positionMs, trackId)
    }

    private fun scheduleAction(action: String, executeAt: Long, positionMs: Long, trackId: String?) {
        scope.launch {
            val currentTime = timeSynchronizer?.getSyncedTime() ?: System.currentTimeMillis()
            val delayMs = max(0L, executeAt - currentTime)
            
            // If changing track, load it BEFORE the delay so it's ready to play
            if (action == "CHANGE_TRACK" && trackId != null) {
                onTrackChangeRequested?.invoke(trackId)
            }
            
            delay(delayMs)
            
            try {
                when (action) {
                    "PLAY" -> {
                        player.seekTo(positionMs)
                        player.play()
                    }
                    "PAUSE" -> {
                        player.pause()
                        player.seekTo(positionMs)
                    }
                    "SEEK" -> {
                        player.seekTo(positionMs)
                    }
                    "CHANGE_TRACK" -> {
                        player.seekTo(0)
                        player.play() 
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun release() {
        timeSynchronizer?.stop()
        networkManager?.stop()
        scope.cancel()
    }
}
