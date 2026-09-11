package com.syncbeat.offline

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    // This is the File Picker that opens your phone's storage
    private val selectAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            playLocalAudio(it)
            // Here we would also tell syncManager to send the file to connected devices!
            Toast.makeText(this, "Playing song...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize the Audio Player
        player = ExoPlayer.Builder(this).build()

        // 1. Golden Neon Sparkle Background
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

        // 2. Glowing Title Text
        val titleText = TextView(this).apply {
            text = "SyncBeat ✨"
            textSize = 42f
            setTextColor(Color.parseColor("#FBBF24"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 120)
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#D97706")) 
        }

        // 3. Liquid Glassmorphism Button Factory
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
            visibility = View.GONE // Hidden until you host a room!
        }

        mainLayout.addView(titleText)
        mainLayout.addView(btnHost)
        mainLayout.addView(btnJoin)
        mainLayout.addView(btnPlaySong)
        setContentView(mainLayout)

        syncManager = AudioSyncManager(this)

        btnHost.setOnClickListener {
            if (checkPermissions()) {
                syncManager.startHosting("HostDevice", 
                    onSuccess = { 
                        Toast.makeText(this, "✅ Golden Room Hosted!", Toast.LENGTH_SHORT).show()
                        btnPlaySong.visibility = View.VISIBLE // Show the play button!
                    },
                    onFailure = { e -> Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            }
        }

        btnJoin.setOnClickListener {
            if (checkPermissions()) {
                syncManager.startDiscovering(
                    onSuccess = { Toast.makeText(this, "🔍 Searching for host...", Toast.LENGTH_SHORT).show() },
                    onFailure = { e -> Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            }
        }

        // When you click the new Play button, open the audio file picker
        btnPlaySong.setOnClickListener {
            selectAudioLauncher.launch("audio/*")
        }
    }

    // Function to load the selected file into the ExoPlayer
    private fun playLocalAudio(uri: Uri) {
        player?.stop()
        val mediaItem = MediaItem.fromUri(uri)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release() // Free up memory when the app closes
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
