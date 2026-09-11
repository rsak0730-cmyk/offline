package com.syncbeat.offline

import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

data class Song(val uri: Uri, val title: String, val albumId: Long, val duration: Long)

class SongAdapter(
    val songs: MutableList<Song>,
    val onClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.VH>() {

    inner class VH(val layout: LinearLayout, val title: TextView, val albumArt: ImageView) : RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 25, 40, 25)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        }

        val albumArtView = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 35, 0) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#222222")) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 20f) }
            }
        }

        val title = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        layout.addView(albumArtView)
        layout.addView(title)
        return VH(layout, title, albumArtView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.albumArt.setImageURI(null) 
        
        // Handles Vidmate/Social Media downloaded audio thumbnails
        Thread {
            try {
                var loaded = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val bitmap = holder.layout.context.contentResolver.loadThumbnail(song.uri, Size(200, 200), null)
                        Handler(Looper.getMainLooper()).post { holder.albumArt.setImageBitmap(bitmap) }
                        loaded = true
                    } catch (e: Exception) { }
                }
                if (!loaded) {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(holder.layout.context, song.uri)
                    val artBytes = mmr.embeddedPicture
                    mmr.release()
                    if (artBytes != null) {
                        val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                        Handler(Looper.getMainLooper()).post { holder.albumArt.setImageBitmap(bitmap) }
                    }
                }
            } catch (e: Exception) {}
        }.start()

        holder.layout.setOnClickListener { onClick(song) }
    }
    override fun getItemCount() = songs.size
}

class MainActivity : ComponentActivity() {

    private lateinit var syncManager: AudioSyncManager
    private var player: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private val syncHandler = Handler(Looper.getMainLooper())
    
    private var isHostDevice = false
    private var isClientDevice = false
    private var spatialPosition = "CENTER"
    private val librarySongs = mutableListOf<Song>()
    private lateinit var libraryAdapter: SongAdapter

    // UI Elements
    private lateinit var libraryScreen: LinearLayout
    private lateinit var nowPlayingScreen: LinearLayout
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var mainTitle: TextView
    private lateinit var mainAlbumArt: ImageView
    private lateinit var miniAlbumArt: ImageView
    private lateinit var btnPlayPauseFull: Button
    private lateinit var btnNextFull: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var nowPlayingBg: GradientDrawable
    private var currentBgColor = Color.parseColor("#121212")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        syncManager = AudioSyncManager(this)

        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ================= SCREEN 1: LIBRARY =================
        libraryScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val headerText = TextView(this).apply {
            text = "Tracks"
            textSize = 32f
            setTextColor(Color.WHITE)
            setPadding(40, 60, 40, 30)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val libraryRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        // Mini Player (Anchored to bottom of library)
        miniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 20, 30, 20)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160)
            background = GradientDrawable().apply { setColor(Color.parseColor("#1E1E1E")); cornerRadius = 40f }
            setOnClickListener { 
                libraryScreen.visibility = View.GONE
                nowPlayingScreen.visibility = View.VISIBLE
            }
        }
        
        miniAlbumArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(0, 0, 30, 0) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 15f; setColor(Color.DKGRAY) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 15f) }
            }
        }
        miniTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val miniPlayPause = Button(this).apply {
            text = "⏸"
            setTextColor(Color.WHITE)
            background = null
            layoutParams = LinearLayout.LayoutParams(100, 100)
            setOnClickListener { togglePlayPause() }
        }
        miniPlayer.addView(miniAlbumArt)
        miniPlayer.addView(miniTitle)
        miniPlayer.addView(miniPlayPause)

        libraryScreen.addView(headerText)
        libraryScreen.addView(libraryRecyclerView)
        libraryScreen.addView(miniPlayer)


        // ================= SCREEN 2: NOW PLAYING =================
        nowPlayingBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(currentBgColor, Color.BLACK)
        )
        
        nowPlayingScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            background = nowPlayingBg
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(40, 60, 40, 40)
        }

        val btnCollapse = Button(this).apply {
            text = "˅"
            textSize = 24f
            setTextColor(Color.WHITE)
            background = null
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { gravity = Gravity.START }
            setOnClickListener { 
                nowPlayingScreen.visibility = View.GONE
                libraryScreen.visibility = View.VISIBLE 
            }
        }

        mainAlbumArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(800, 800).apply { setMargins(0, 40, 0, 60) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.parseColor("#222222")) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 40f) }
            }
        }

        mainTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Seekbar & Time
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 40, 0, 10) }
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) { if(p2 && !isClientDevice) player?.seekTo(p1.toLong()) }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        timeText = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 12f; text = "0:00 / 0:00"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }

        // Playback Controls
        val playControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40) }
        btnPlayPauseFull = Button(this).apply { text = "⏸"; textSize = 30f; setTextColor(Color.WHITE); background = null; setOnClickListener { togglePlayPause() } }
        btnNextFull = Button(this).apply { text = "⏭"; textSize = 24f; setTextColor(Color.WHITE); background = null; setMargins(40, 0, 0, 0); setOnClickListener { player?.seekToNextMediaItem() } }
        playControls.addView(btnPlayPauseFull)
        playControls.addView(btnNextFull)

        // Sync & EQ Controls (Scrollable for smaller screens)
        val advancedControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        
        val syncRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val btnHost = createActionBtn("Host Sync")
        val btnJoin = createActionBtn("Join Room")
        val btnLeave = createActionBtn("Leave").apply { visibility = View.GONE }
        syncRow.addView(btnHost); syncRow.addView(btnJoin); syncRow.addView(btnLeave)

        val audioModsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 20, 0, 0) }
        val btnEQ = createActionBtn("🎛 EQ")
        val btn3D = createActionBtn("📍 3D: CTR")
        audioModsRow.addView(btnEQ); audioModsRow.addView(btn3D)
        
        val eqContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 20, 0, 0) }
        
        btnEQ.setOnClickListener {
            eqContainer.visibility = if (eqContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            val sessionId = player?.audioSessionId ?: 0
            if (sessionId != 0 && equalizer == null) setupEqualizer(sessionId)
            
            equalizer?.let { eq ->
                eqContainer.removeAllViews()
                val minEQ = eq.bandLevelRange[0]
                val maxEQ = eq.bandLevelRange[1]
                val seekBars = mutableListOf<SeekBar>()

                // FEATURE: Reset EQ
                val resetBtn = Button(this).apply {
                    text = "Reset EQ"; setTextColor(Color.WHITE); background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#444444")) }
                    setOnClickListener {
                        for (i in 0 until eq.numberOfBands) { eq.setBandLevel(i.toShort(), 0); seekBars[i].progress = 0 - minEQ }
                    }
                }
                eqContainer.addView(resetBtn)

                for (i in 0 until eq.numberOfBands) {
                    val band = i.toShort()
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val label = TextView(this@MainActivity).apply { text = "${eq.getCenterFreq(band)/1000}Hz"; setTextColor(Color.WHITE); width = 120 }
                    val sb = SeekBar(this@MainActivity).apply {
                        max = maxEQ - minEQ; progress = eq.getBandLevel(band) - minEQ
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if (user) eq.setBandLevel(band, (p + minEQ).toShort()) }
                            override fun onStartTrackingTouch(s: SeekBar?) {}
                            override fun onStopTrackingTouch(s: SeekBar?) {}
                        })
                    }
                    seekBars.add(sb); row.addView(label); row.addView(sb); eqContainer.addView(row)
                }
            }
        }

        btn3D.setOnClickListener {
            spatialPosition = when (spatialPosition) { "CENTER" -> "LEFT"; "LEFT" -> "RIGHT"; "RIGHT" -> "REAR"; else -> "CENTER" }
            btn3D.text = "📍 3D: ${spatialPosition.take(3)}"
            player?.volume = when (spatialPosition) { "LEFT", "RIGHT" -> 0.85f; "REAR" -> 0.5f; else -> 1.0f }
        }

        advancedControls.addView(syncRow)
        advancedControls.addView(audioModsRow)
        advancedControls.addView(eqContainer)

        val scrollAdvance = ScrollView(this).apply { addView(advancedControls) }

        nowPlayingScreen.addView(btnCollapse)
        nowPlayingScreen.addView(mainAlbumArt)
        nowPlayingScreen.addView(mainTitle)
        nowPlayingScreen.addView(seekBar)
        nowPlayingScreen.addView(timeText)
        nowPlayingScreen.addView(playControls)
        nowPlayingScreen.addView(scrollAdvance)

        rootLayout.addView(libraryScreen)
        rootLayout.addView(nowPlayingScreen)
        setContentView(rootLayout)

        // ================= LOGIC & LISTENERS =================
        libraryAdapter = SongAdapter(librarySongs) { song ->
            miniPlayer.visibility = View.VISIBLE
            updateUIForSong(song)
            val mediaItem = MediaItem.fromUri(song.uri)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
        }
        libraryRecyclerView.adapter = libraryAdapter

        if (checkPermissions()) loadLocalMusic()

        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.localConfiguration?.uri?.let { uri ->
                    val song = librarySongs.find { it.uri == uri }
                    song?.let { 
                        updateUIForSong(it)
                        val newColor = extractAverageColor(it.uri)
                        animateBackgroundColor(newColor)
                    }
                    if (isHostDevice) syncManager.broadcastAudioFile(uri)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val icon = if(isPlaying) "⏸" else "▶"
                miniPlayPause.text = icon
                btnPlayPauseFull.text = icon
            }
        })

        // Progress Handler
        val progressHandler = Handler(Looper.getMainLooper())
        progressHandler.post(object : Runnable {
            override fun run() {
                player?.let {
                    if (it.isPlaying) {
                        seekBar.max = it.duration.toInt()
                        seekBar.progress = it.currentPosition.toInt()
                        timeText.text = "${formatTime(it.currentPosition)} / ${formatTime(it.duration)}"
                    }
                }
                progressHandler.postDelayed(this, 1000)
            }
        })

        syncManager.onAudioReceived = { uri -> runOnUiThread { 
            val mediaItem = MediaItem.fromUri(uri)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            miniTitle.text = "Syncing from Host..."
            mainTitle.text = "Syncing from Host..."
            miniPlayer.visibility = View.VISIBLE
        }}

        syncManager.onSyncTickReceived = { hostMs ->
            if (isClientDevice) {
                val drift = (player?.currentPosition ?: 0) - hostMs
                if (kotlin.math.abs(drift) > 150) player?.seekTo(hostMs)
            }
        }

        btnHost.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = true; isClientDevice = false
                syncManager.startHosting("Host", { 
                    btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE
                    startHostSyncLoop()
                }, {})
            }
        }

        // FEATURE: Hide controls on Join
        btnJoin.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = false; isClientDevice = true
                syncManager.startDiscovering({
                    btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE
                    btnPlayPauseFull.visibility = View.GONE
                    btnNextFull.visibility = View.GONE
                    miniPlayPause.visibility = View.GONE
                    seekBar.isEnabled = false
                }, {})
            }
        }

        btnLeave.setOnClickListener {
            syncManager.stopAllConnections(); player?.stop()
            isHostDevice = false; isClientDevice = false
            btnHost.visibility = View.VISIBLE; btnJoin.visibility = View.VISIBLE; btnLeave.visibility = View.GONE
            btnPlayPauseFull.visibility = View.VISIBLE
            btnNextFull.visibility = View.VISIBLE
            miniPlayPause.visibility = View.VISIBLE
            seekBar.isEnabled = true
        }
    }

    private fun createActionBtn(text: String): Button {
        return Button(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 12f; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, 110, 1f).apply { setMargins(10, 0, 10, 0) }
            background = GradientDrawable().apply { cornerRadius = 30f; setColor(Color.argb(40, 255, 255, 255)) }
        }
    }

    private fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun updateUIForSong(song: Song) {
        miniTitle.text = song.title
        mainTitle.text = song.title
        Thread {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(this, song.uri)
                val art = mmr.embeddedPicture
                mmr.release()
                val bitmap = if (art != null) BitmapFactory.decodeByteArray(art, 0, art.size) else null
                runOnUiThread { 
                    mainAlbumArt.setImageBitmap(bitmap)
                    miniAlbumArt.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun setupEqualizer(sessionId: Int) {
        try { equalizer?.release(); equalizer = Equalizer(0, sessionId).apply { enabled = true } } catch (_: Exception) {}
    }

    private fun extractAverageColor(uri: Uri): Int {
        return try {
            val mmr = MediaMetadataRetriever().apply { setDataSource(this@MainActivity, uri) }
            val art = mmr.embeddedPicture
            mmr.release()
            if (art != null) {
                val bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                val color = scaled.getPixel(0, 0)
                scaled.recycle(); bitmap.recycle()
                color
            } else Color.parseColor("#121212")
        } catch (_: Exception) { Color.parseColor("#121212") }
    }

    private fun animateBackgroundColor(targetColor: Int) {
        ValueAnimator.ofArgb(currentBgColor, targetColor).apply {
            duration = 800
            addUpdateListener { animator -> 
                nowPlayingBg.colors = intArrayOf(animator.animatedValue as Int, Color.BLACK)
            }
            start()
        }
        currentBgColor = targetColor
    }

    private fun loadLocalMusic() {
        librarySongs.clear()
        val cursor = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION), 
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0); val title = it.getString(1); val albumId = it.getLong(2); val dur = it.getLong(3)
                librarySongs.add(Song(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), title, albumId, dur))
            }
        }
        libraryAdapter.notifyDataSetChanged()
    }

    private fun startHostSyncLoop() {
        syncHandler.removeCallbacksAndMessages(null)
        syncHandler.post(object : Runnable {
            override fun run() {
                if (isHostDevice && player?.isPlaying == true) player?.currentPosition?.let { syncManager.sendSyncTick(it) }
                syncHandler.postDelayed(this, 1000)
            }
        })
    }

    override fun onDestroy() { super.onDestroy(); player?.release(); equalizer?.release() }

    private fun checkPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.addAll(listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.READ_MEDIA_AUDIO))
        } else perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms.addAll(listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1); return false }
        return true
    }
}

