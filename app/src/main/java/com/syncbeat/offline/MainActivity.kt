package com.syncbeat.offline

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : ComponentActivity() {

    private lateinit var syncManager: AudioSyncManager
    private var player: ExoPlayer? = null
    
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isHostDevice = false
    private var spatialPosition = "CENTER"

    private val selectAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            playAudioWithPlacement(it, spatialPosition)
            syncManager.broadcastAudioFile(it)
            startHostSyncLoop()
            Toast.makeText(this, "Streaming spatial channels...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        player = ExoPlayer.Builder(this).build()

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#1C1404"), 
                    Color.parseColor("#4A3B0F"), 
                    Color.parseColor("#080602")
                )
            ).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 1200f
            }
        }

        val titleText = TextView(this).apply {
            text = "SyncBeat Spatial ✨"
            textSize = 32f
            setTextColor(Color.parseColor("#FBBF24"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#D97706")) 
        }

        val placementInstruction = TextView(this).apply {
            text = "📍 Place this phone: CENTER"
            textSize = 15f
            setTextColor(Color.parseColor("#FDE68A"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }

        fun createGlassButton(buttonText: String): Button {
            return Button(this).apply {
                text = buttonText
                setTextColor(Color.WHITE)
                textSize = 14f
                isAllCaps = false
                
                val marginParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    130
                ).apply { setMargins(0, 0, 0, 20) }
                layoutParams = marginParams

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 50f
                    setColor(Color.argb(45, 255, 255, 255))
                    setStroke(4, Color.argb(150, 251, 191, 36))
                }
                elevation = 15f
            }
        }

        val btnPosition = createGlassButton("🔄 Target: CENTER")
        val btnHost = createGlassButton("✨ Host Room")
        val btnJoin = createGlassButton("🔮 Join Room")
        val btnLeave = createGlassButton("🚪 Leave Room").apply { visibility = View.GONE }
        val btnPlaySong = createGlassButton("🎵 Select & Play Song").apply { visibility = View.GONE }
        val btnPlayPause = createGlassButton("⏸️ Play / Pause").apply { visibility = View.GONE }

        mainLayout.addView(titleText)
        mainLayout.addView(placementInstruction)
        mainLayout.addView(btnPosition)
        mainLayout.addView(btnHost)
        mainLayout.addView(btnJoin)
        mainLayout.addView(btnLeave)
        mainLayout.addView(btnPlaySong)
        mainLayout.addView(btnPlayPause)
        setContentView(mainLayout)

        syncManager = AudioSyncManager(this)

        btnPosition.setOnClickListener {
            spatialPosition = when (spatialPosition) {
                "CENTER" -> "LEFT"
                "LEFT" -> "RIGHT"
                "RIGHT" -> "REAR"
                else -> "CENTER"
            }
            btnPosition.text = "🔄 Target: $spatialPosition"
            placementInstruction.text = when(spatialPosition) {
                "LEFT" -> "📍 Place this phone on your LEFT side"
                "RIGHT" -> "📍 Place this phone on your RIGHT side"
                "REAR" -> "📍 Place this phone BEHIND you"
                else -> "📍 Place this phone in the CENTER"
            }
        }

        syncManager.onAudioReceived = { receivedUri ->
            runOnUiThread {
                Toast.makeText(this, "Playing as $spatialPosition speaker", Toast.LENGTH_SHORT).show()
                playAudioWithPlacement(receivedUri, spatialPosition)
                btnPlaySong.visibility = View.GONE
                btnPlayPause.visibility = View.VISIBLE
            }
        }

        syncManager.onSyncTickReceived = { hostPositionMs ->
            if (!isHostDevice) {
                player?.let { localPlayer ->
                    val drift = localPlayer.currentPosition - hostPositionMs
                    if (kotlin.math.abs(drift) > 150) {
                        localPlayer.seekTo(hostPositionMs)
                    }
                }
            }
        }

        btnHost.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = true
                syncManager.startHosting("HostDevice", 
                    onSuccess = { 
                        Toast.makeText(this, "✅ Room Hosted", Toast.LENGTH_SHORT).show()
                        btnHost.visibility = View.GONE
                        btnJoin.visibility = View.GONE
                        btnPosition.visibility = View.GONE
                        placementInstruction.visibility = View.GONE
                        btnLeave.visibility = View.VISIBLE
                        btnPlaySong.visibility = View.VISIBLE
                        btnPlayPause.visibility = View.VISIBLE
                    },
                    onFailure = { e -> Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            }
        }

        btnJoin.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = false
                syncManager.startDiscovering(
                    onSuccess = { 
                        Toast.makeText(this, "🔍 Searching...", Toast.LENGTH_SHORT).show()
                        btnHost.visibility = View.GONE
                        btnJoin.visibility = View.GONE
                        btnPosition.visibility = View.GONE
                        placementInstruction.visibility = View.GONE
                        btnLeave.visibility = View.VISIBLE
                    },
                    onFailure = { e -> Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            }
        }

        btnLeave.setOnClickListener {
            syncManager.stopAllConnections()
            player?.stop()
            syncHandler.removeCallbacks(hostSyncRunnable)
            
            btnHost.visibility = View.VISIBLE
            btnJoin.visibility = View.VISIBLE
            btnPosition.visibility = View.VISIBLE
            placementInstruction.visibility = View.VISIBLE
            btnLeave.visibility = View.GONE
            btnPlaySong.visibility = View.GONE
            btnPlayPause.visibility = View.GONE
            Toast.makeText(this, "🚪 Left Room", Toast.LENGTH_SHORT).show()
        }

        btnPlaySong.setOnClickListener {
            selectAudioLauncher.launch("audio/*")
        }

        btnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
    }

    private fun playAudioWithPlacement(uri: Uri, position: String) {
        player?.stop()
        val mediaItem = MediaItem.fromUri(uri)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        
        player?.volume = when (position) {
            "LEFT" -> 0.85f
            "RIGHT" -> 0.85f
            "REAR" -> 0.5f
            else -> 1.0f
        }
        player?.play()
    }

    private val hostSyncRunnable = object : Runnable {
        override fun run() {
            if (isHostDevice && player?.isPlaying == true) {
                player?.currentPosition?.let { pos -> syncManager.sendSyncTick(pos) }
            }
            syncHandler.postDelayed(this, 2000)
        }
    }

    private fun startHostSyncLoop() {
        syncHandler.removeCallbacks(hostSyncRunnable)
        syncHandler.post(hostSyncRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        syncHandler.removeCallbacks(hostSyncRunnable)
        player?.release()
    }

    private fun checkPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            requiredPermissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1)
            return false
        }
        return true
    }
}

