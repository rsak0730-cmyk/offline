package com.syncbeat.offline

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class TimeSynchronizer(private val isHost: Boolean) {
    var clockOffsetMs: Long = 0
        private set

    private val syncPort = 9876
    private var socket: DatagramSocket? = null

    fun getSyncedTime(): Long = System.currentTimeMillis() + clockOffsetMs

    fun start() {
        socket = DatagramSocket(if (isHost) syncPort else 0)
        if (isHost) {
            startHostServer()
        }
    }

    private fun startHostServer() {
        thread {
            val buffer = ByteArray(1024)
            while (true) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val t1 = System.currentTimeMillis()
                    
                    val clientT0 = String(packet.data, 0, packet.length).toLong()
                    val t2 = System.currentTimeMillis()
                    val reply = "$clientT0,$t1,$t2".toByteArray()
                    
                    val replyPacket = DatagramPacket(reply, reply.size, packet.address, packet.port)
                    socket?.send(replyPacket)
                } catch (e: Exception) { break }
            }
        }
    }

    fun syncWithHost(hostIp: String) {
        if (isHost) return
        thread {
            try {
                val address = InetAddress.getByName(hostIp)
                var totalOffset: Long = 0
                val iterations = 5 

                for (i in 0 until iterations) {
                    val t0 = System.currentTimeMillis()
                    val msg = t0.toString().toByteArray()
                    val packet = DatagramPacket(msg, msg.size, address, syncPort)
                    socket?.send(packet)

                    val buffer = ByteArray(1024)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket?.receive(response)
                    val t3 = System.currentTimeMillis()

                    val parts = String(response.data, 0, response.length).split(",")
                    val origT0 = parts[0].toLong()
                    val t1 = parts[1].toLong()
                    val t2 = parts[2].toLong()

                    val offset = ((t1 - origT0) + (t2 - t3)) / 2
                    totalOffset += offset
                    Thread.sleep(50) 
                }
                clockOffsetMs = totalOffset / iterations
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
    
    fun stop() {
        socket?.close()
    }
}

