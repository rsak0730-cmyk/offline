package com.syncbeat.offline

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

// 1. Data Model for a Song
data class Song(val uri: Uri, val title: String)

class MainActivity : ComponentActivity() {

    private lateinit var syncManager: AudioSyncManager
    private var player: ExoPlayer? = null
    
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isHostDevice = false
    private var spatialPosition = "CENTER"

    // 2. Library and Queue Data
    private val librarySongs = mutableListOf<Song>()
    private val queueSongs = mutableListOf<Song>()

    private lateinit var libraryAdapter: SongAdapter
    private lateinit var queueAdapter: SongAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        player = ExoPlayer.Builder(this).build()

        // Automatically broadcast the next song in the queue to peers when the track changes!
        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                if (isHostDevice && mediaItem != null) {
                    val uri = mediaItem.localConfiguration?.uri
                    if (uri != null) {
                        syncManager.broadcastAudioFile(uri)
                        Toast.makeText(this@MainActivity, "Broadcasting next track...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        // ================= UI SETUP =================
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1C1404"), Color.parseColor("#4A3B0F"), Color.parseColor("#080602"))
            ).apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 1200f }
        }

        val titleText = TextView(this).apply {
            text = "SyncBeat Pro ✨"
            textSize = 28f
            setTextColor(Color.parseColor("#FBBF24"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#D97706")) 
        }

        fun createGlassButton(buttonText: String): Button {
            return Button(this).apply {
                text = buttonText
                setTextColor(Color.WHITE)
                textSize = 12f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, 120, 1f).apply { setMargins(10, 0, 10, 20) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 40f
                    setColor(Color.argb(45, 255, 255, 255))
                    setStroke(3, Color.argb(150, 251, 191, 36))
                }
            }
        }

        // Top Controls Row
        val controlsRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnHost = createGlassButton("✨ Host")
        val btnJoin = createGlassButton("🔮 Join")
        val btnLeave = createGlassButton("🚪 Leave").apply { visibility = View.GONE }
        controlsRow1.addView(btnHost)
        controlsRow1.addView(btnJoin)
        controlsRow1.addView(btnLeave)

        // Playback Controls Row
        val controlsRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPlayPause = createGlassButton("⏸️ Play/Pause")
        val btnNext = createGlassButton("⏭️ Skip Next")
        controlsRow2.addView(btnPlayPause)
        controlsRow2.addView(btnNext)

        // Lists Layout (Takes up the rest of the screen height)
        val listsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // --- QUEUE UI ---
        val queueTitle = TextView(this).apply {
            text = "📜 Up Next (Drag to Reorder)"
            setTextColor(Color.parseColor("#FDE68A"))
            setPadding(0, 20, 0, 10)
        }
        val queueRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        // --- LIBRARY UI ---
        val libraryTitle = TextView(this).apply {
            text = "🎵 All Songs (Tap to Add)"
            setTextColor(Color.parseColor("#FDE68A"))
            setPadding(0, 20, 0, 10)
        }
        val libraryRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f)
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        listsLayout.addView(queueTitle)
        listsLayout.addView(queueRecyclerView)
        listsLayout.addView(libraryTitle)
        listsLayout.addView(libraryRecyclerView)

        mainLayout.addView(titleText)
        mainLayout.addView(controlsRow1)
        mainLayout.addView(controlsRow2)
        mainLayout.addView(listsLayout)
        setContentView(mainLayout)

        syncManager = AudioSyncManager(this)

        // ================= ADAPTERS & LOGIC =================
        
        // 3. Adapter for lists
        class SongAdapter(val songs: MutableList<Song>, val isQueue: Boolean, val onClick: (Song) -> Unit) : RecyclerView.Adapter<SongAdapter.VH>() {
            inner class VH(val layout: LinearLayout, val title: TextView) : RecyclerView.ViewHolder(layout)
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val layout = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(30, 30, 30, 30)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,15) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 30f
                        setColor(Color.argb(if (isQueue) 60 else 20, 255, 255, 255))
                        setStroke(2, Color.argb(100, 251, 191, 36))
                    }
                }
                val title = TextView(parent.context).apply { setTextColor(Color.WHITE); textSize = 14f }
                layout.addView(title)
                return VH(layout, title)
            }
            override fun onBindViewHolder(holder: VH, position: Int) {
                val song = songs[position]
                holder.title.text = if (isQueue) "${position + 1}. ${song.title}" else "🎵 ${song.title}"
                holder.layout.setOnClickListener { onClick(song) }
            }
            override fun getItemCount() = songs.size
        }

        // 4. Initialize Adapters
        queueAdapter = SongAdapter(queueSongs, true) { /* Clicking queue item does nothing for now */ }
        queueRecyclerView.adapter = queueAdapter

        libraryAdapter = SongAdapter(librarySongs, false) { song ->
            // Add to Queue and ExoPlayer!
            val mediaItem = MediaItem.fromUri(song.uri)
            player?.addMediaItem(mediaItem)
            queueSongs.add(song)
            queueAdapter.notifyItemInserted(queueSongs.size - 1)
            
            // Auto-play if it's the first song added
            if (player?.playbackState == Player.STATE_IDLE) {
                player?.prepare()
                player?.play()
            }
            Toast.makeText(this, "Added to Queue", Toast.LENGTH_SHORT).show()
        }
        libraryRecyclerView.adapter = libraryAdapter

        // 5. Drag and Drop for the Queue!
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                Collections.swap(queueSongs, from, to)
                queueAdapter.notifyItemMoved(from, to)
                // Sync the reorder with ExoPlayer's internal engine
                player?.moveMediaItem(from, to)
                
                // Update numbers on the views
                queueAdapter.notifyItemRangeChanged(0, queueSongs.size)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(queueRecyclerView)

        // ================= PERMISSIONS & LOADING =================
        if (checkPermissions()) { loadLocalMusic() }

        // ================= BUTTON LISTENERS =================
        btnPlayPause.setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        btnNext.setOnClickListener { player?.seekToNextMediaItem() }

        syncManager.onAudioReceived = { receivedUri ->
            runOnUiThread {
                Toast.makeText(this, "Playing synced track!", Toast.LENGTH_SHORT).show()
                playAudioWithPlacement(receivedUri, spatialPosition)
            }
        }

        syncManager.onSyncTickReceived = { hostPositionMs ->
            if (!isHostDevice) {
                player?.let { localPlayer ->
                    val drift = localPlayer.currentPosition - hostPositionMs
                    if (kotlin.math.abs(drift) > 150) localPlayer.seekTo(hostPositionMs)
                }
            }
        }

        btnHost.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = true
                syncManager.startHosting("HostDevice", 
                    onSuccess = { 
                        btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE
                        startHostSyncLoop()
                    },
                    onFailure = { e -> Toast.makeText(this, "❌ Error", Toast.LENGTH_SHORT).show() }
                )
            }
        }

        btnJoin.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = false
                syncManager.startDiscovering(
                    onSuccess = { btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE },
                    onFailure = { e -> Toast.makeText(this, "❌ Error", Toast.LENGTH_SHORT).show() }
                )
            }
        }

        btnLeave.setOnClickListener {
            syncManager.stopAllConnections()
            player?.stop()
            syncHandler.removeCallbacks(hostSyncRunnable)
            btnHost.visibility = View.VISIBLE; btnJoin.visibility = View.VISIBLE; btnLeave.visibility = View.GONE
        }
    }

    // 6. Reads your phone's storage to find all MP3 files
    private fun loadLocalMusic() {
        librarySongs.clear()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val cursor = contentResolver.query(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null, MediaStore.Audio.Media.TITLE + " ASC"
        )
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                librarySongs.add(Song(uri, title))
            }
        }
        libraryAdapter.notifyDataSetChanged()
    }

    private fun playAudioWithPlacement(uri: Uri, position: String) {
        player?.stop()
        val mediaItem = MediaItem.fromUri(uri)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.volume = when (position) { "LEFT" -> 0.85f; "RIGHT" -> 0.85f; "REAR" -> 0.5f; else -> 1.0f }
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
        val requiredPermissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
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
        val missingPermissions = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1)
            return false
        }
        return true
    }
}

