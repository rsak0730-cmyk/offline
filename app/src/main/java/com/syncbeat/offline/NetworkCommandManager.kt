package com.syncbeat.offline

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class NetworkCommandManager(
    private val isHost: Boolean,
    private val onCommandReceived: (String, Long, Long, String?) -> Unit
) {
    private val commandPort = 9877
    private var socket: DatagramSocket? = null

    fun start() {
        socket = DatagramSocket(if (isHost) 0 else commandPort)
        if (!isHost) {
            listenForCommands()
        }
    }

    private fun listenForCommands() {
        thread {
            val buffer = ByteArray(1024)
            while (true) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val jsonString = String(packet.data, 0, packet.length)
                    val json = JSONObject(jsonString)
                    
                    val action = json.getString("action")
                    val executeAt = json.getLong("executeAt")
                    val positionMs = json.optLong("positionMs", 0L)
                    val trackId = json.optString("trackId", null)
                    
                    onCommandReceived(action, executeAt, positionMs, trackId)
                } catch (e: Exception) { break }
            }
        }
    }

    fun broadcastCommand(broadcastIp: String, action: String, executeAt: Long, positionMs: Long = 0L, trackId: String? = null) {
        if (!isHost) return
        thread {
            try {
                val json = JSONObject().apply {
                    put("action", action)
                    put("executeAt", executeAt)
                    put("positionMs", positionMs)
                    if (trackId != null) put("trackId", trackId)
                }
                
                val msg = json.toString().toByteArray()
                val address = InetAddress.getByName(broadcastIp)
                val packet = DatagramPacket(msg, msg.size, address, commandPort)
                
                socket?.send(packet)
                Thread.sleep(5)
                socket?.send(packet)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun stop() {
        socket?.close()
    }
}

