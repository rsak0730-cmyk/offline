package com.syncbeat.offline

import android.media.MediaPlayer
import kotlinx.coroutines.*
import kotlin.math.max

class AudioSyncManager(
    private val isHost: Boolean,
    private val mediaPlayer: MediaPlayer,
    private val onTrackChangeRequested: ((String) -> Unit)? = null // Callback to tell UI to load new track
) {
    private val timeSynchronizer = TimeSynchronizer(isHost)
    private lateinit var networkManager: NetworkCommandManager
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // 150ms buffer gives the network time to deliver the UDP packet before execution
    private val NETWORK_BUFFER_MS = 150L 
    private var currentBroadcastIp: String = "255.255.255.255"

    fun initialize(hostIp: String? = null, broadcastIp: String = "255.255.255.255") {
        this.currentBroadcastIp = broadcastIp
        timeSynchronizer.start()
        
        if (!isHost && hostIp != null) {
            timeSynchronizer.syncWithHost(hostIp)
        }

        networkManager = NetworkCommandManager(isHost) { action, executeAt, positionMs, trackId ->
            scheduleAction(action, executeAt, positionMs, trackId)
        }
        networkManager.start()
    }

    // --- HOST CONTROLS ---

    fun hostPlay() {
        triggerAction("PLAY", currentPosition())
    }

    fun hostPause() {
        triggerAction("PAUSE", currentPosition())
    }

    fun hostSeekForward(jumpMs: Long = 10000L) {
        triggerAction("SEEK", currentPosition() + jumpMs)
    }

    fun hostSeekBackward(jumpMs: Long = 10000L) {
        triggerAction("SEEK", max(0L, currentPosition() - jumpMs))
    }

    fun hostNextTrack(nextTrackId: String) {
        triggerAction("CHANGE_TRACK", 0L, nextTrackId)
    }

    // --- INTERNAL SCHEDULING LOGIC ---

    private fun currentPosition(): Long {
        return try { mediaPlayer.currentPosition.toLong() } catch (e: Exception) { 0L }
    }

    private fun triggerAction(action: String, positionMs: Long, trackId: String? = null) {
        if (!isHost) return
        
        val executeAt = timeSynchronizer.getSyncedTime() + NETWORK_BUFFER_MS
        networkManager.broadcastCommand(currentBroadcastIp, action, executeAt, positionMs, trackId)
        scheduleAction(action, executeAt, positionMs, trackId)
    }

    private fun scheduleAction(action: String, executeAt: Long, positionMs: Long, trackId: String?) {
        scope.launch {
            val currentTime = timeSynchronizer.getSyncedTime()
            val delayMs = max(0L, executeAt - currentTime)
            
            // If changing track, load it BEFORE the delay so it's ready to play
            if (action == "CHANGE_TRACK" && trackId != null) {
                onTrackChangeRequested?.invoke(trackId)
            }
            
            delay(delayMs)
            
            try {
                when (action) {
                    "PLAY" -> {
                        mediaPlayer.seekTo(positionMs.toInt())
                        mediaPlayer.start()
                    }
                    "PAUSE" -> {
                        mediaPlayer.pause()
                        mediaPlayer.seekTo(positionMs.toInt()) // Keep everyone at the exact paused frame
                    }
                    "SEEK" -> {
                        mediaPlayer.seekTo(positionMs.toInt())
                    }
                    "CHANGE_TRACK" -> {
                        mediaPlayer.start() // Track was already loaded, just hit play
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun release() {
        timeSynchronizer.stop()
        networkManager.stop()
        scope.cancel()
    }
}
