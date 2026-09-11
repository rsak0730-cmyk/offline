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
    
    // Handler to run the Host's periodic clock sync loop
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isHostDevice = false

    private val selectAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            playLocalAudio(it)
            syncManager.broadcastAudioFile(it)
            startHostSyncLoop() // Start pushing time stamps once music plays
            Toast.makeText(this, "Playing and broadcasting...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        player = ExoPlayer.Builder(this).build()

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 80, 80, 80)
            
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
            text = "SyncBeat ✨"
            textSize = 42f
            setTextColor(Color.parseColor("#FBBF24"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 120)
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#D97706")) 
        }

        fun createGlassButton(buttonText: String): Button {
            return Button(this).apply {
                text = buttonText
                setTextColor(Color.WHITE)
                textSize = 17f
                isAllCaps = false
                
                val marginParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    180
                ).apply { setMargins(0, 0, 0, 50) }
                layoutParams = marginParams

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 80f
                    setColor(Color.argb(45, 255, 255, 255))
                    setStroke(4, Color.argb(150, 251, 191, 36))
                }
                elevation = 15f
            }
        }

        val btnHost = createGlassButton("✨ Host Golden Room")
        val btnJoin = createGlassButton("🔮 Join Room")
        val btnPlaySong = createGlassButton("🎵 Select & Play Song").apply {
            visibility = View.GONE
        }

        mainLayout.addView(titleText)
        mainLayout.addView(btnHost)
        mainLayout.addView(btnJoin)
        mainLayout.addView(btnPlaySong)
        setContentView(mainLayout)

        syncManager = AudioSyncManager(this)

        // When a client receives a song file from the host
        syncManager.onAudioReceived = { receivedUri ->
            runOnUiThread {
                Toast.makeText(this, "Song received! Playing...", Toast.LENGTH_SHORT).show()
                playLocalAudio(receivedUri)
            }
        }

        // When a client receives a time stamp tick from the host, correct drift!
        syncManager.onSyncTickReceived = { hostPositionMs ->
            if (!isHostDevice) {
                player?.let { localPlayer ->
                    val drift = localPlayer.currentPosition - hostPositionMs
                    // If drift is greater than 150 milliseconds, force a time correction
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
                        Toast.makeText(this, "✅ Golden Room Hosted!", Toast.LENGTH_SHORT).show()
                        btnPlaySong.visibility = View.VISIBLE
                    },
                    onFailure = { e -> Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            }
        }

        btnJoin.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = false
                syncManager.startDiscovering(
                    onSuccess = { Toast.makeText(this, "🔍 Searching for host...", Toast.LENGTH_SHORT).show() },
                    onFailure = { e -> Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            }
        }

        btnPlaySong.setOnClickListener {
            selectAudioLauncher.launch("audio/*")
        }
    }

    private fun playLocalAudio(uri: Uri) {
        player?.stop()
        val mediaItem = MediaItem.fromUri(uri)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }

    // Host routine: Sends out a sync tick every 2 seconds
    private val hostSyncRunnable = object : Runnable {
        override fun run() {
            if (isHostDevice && player?.isPlaying == true) {
                player?.currentPosition?.let { pos ->
                    syncManager.sendSyncTick(pos)
                }
            }
            syncHandler.postDelayed(this, 2000) // Repeat every 2 seconds
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
