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
import android.util.Size // FIXED: This missing import caused the GitHub Actions error
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
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

data class Song(val uri: Uri, val title: String, val albumId: Long)

class SongAdapter(
    val songs: MutableList<Song>,
    val isQueue: Boolean,
    val onClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.VH>() {

    inner class VH(val layout: LinearLayout, val title: TextView, val albumArt: ImageView) : RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(25, 20, 25, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 15) }
            // Samsung Music inspired clean list items
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 35f
                setColor(Color.argb(if (isQueue) 40 else 15, 255, 255, 255))
            }
        }

        val albumArtView = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 30, 0) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 25f
                setColor(Color.parseColor("#222222"))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 25f)
                }
            }
        }

        val title = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        layout.addView(albumArtView)
        layout.addView(title)
        return VH(layout, title, albumArtView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.title.text = if (isQueue) "${position + 1}. ${song.title}" else song.title
        
        holder.albumArt.setImageURI(null) 
        
        // FEATURE 4 FIX: Advanced Thumbnail Extraction for Vidmate/Downloads
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
    private var spatialPosition = "CENTER"

    private val librarySongs = mutableListOf<Song>()
    private val queueSongs = mutableListOf<Song>()

    private lateinit var libraryAdapter: SongAdapter
    private lateinit var queueAdapter: SongAdapter

    private var currentBgColor = Color.parseColor("#121212")
    private lateinit var mainLayoutBg: GradientDrawable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build()

        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                
                // VIDEO BUG FIX: Remove finished songs from the Up Next Queue
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    runOnUiThread {
                        mediaItem?.localConfiguration?.uri?.let { playingUri ->
                            val playingIndex = queueSongs.indexOfFirst { it.uri == playingUri }
                            if (playingIndex > 0) {
                                queueSongs.subList(0, playingIndex).clear()
                                queueAdapter.notifyItemRangeRemoved(0, playingIndex)
                                queueAdapter.notifyItemRangeChanged(0, queueSongs.size)
                            }
                        }
                    }
                }

                mediaItem?.localConfiguration?.uri?.let { uri ->
                    val song = queueSongs.find { it.uri == uri }
                    if (song != null) {
                        val newColor = extractAverageColor(song.uri)
                        animateBackgroundColor(newColor)
                    }
                    if (isHostDevice) syncManager.broadcastAudioFile(uri)
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                super.onAudioSessionIdChanged(audioSessionId)
                setupEqualizer(audioSessionId)
            }
        })

        mainLayoutBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#0A0A0A"), currentBgColor, Color.parseColor("#000000"))
        ).apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 1400f }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 40)
            background = mainLayoutBg
        }

        val titleText = TextView(this).apply {
            text = "SyncBeat Pro ✨"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val visualizerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80).apply { setMargins(0, 0, 0, 20) }
        }
        val bars = Array(8) {
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(15, 10).apply { setMargins(8, 0, 8, 0) }
                background = GradientDrawable().apply { setColor(Color.parseColor("#FBBF24")); cornerRadius = 10f }
            }
        }
        bars.forEach { visualizerLayout.addView(it) }

        val visualizerHandler = Handler(Looper.getMainLooper())
        val visualizerRunnable = object : Runnable {
            override fun run() {
                if (player?.isPlaying == true) {
                    bars.forEach { bar -> bar.layoutParams = LinearLayout.LayoutParams(15, (10..80).random()).apply { setMargins(8, 0, 8, 0) } }
                } else {
                    bars.forEach { bar -> bar.layoutParams = LinearLayout.LayoutParams(15, 10).apply { setMargins(8, 0, 8, 0) } }
                }
                visualizerHandler.postDelayed(this, 120)
            }
        }
        visualizerHandler.post(visualizerRunnable)

        fun createGlassButton(buttonText: String): Button {
            return Button(this).apply {
                text = buttonText
                setTextColor(Color.WHITE)
                textSize = 12f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, 120, 1f).apply { setMargins(10, 0, 10, 20) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 30f // Samsung Style Rounded Corners
                    setColor(Color.argb(50, 255, 255, 255))
                }
            }
        }

        val controlsRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnHost = createGlassButton("✨ Host")
        val btnJoin = createGlassButton("🔮 Join")
        val btnLeave = createGlassButton("🚪 Leave").apply { visibility = View.GONE }
        controlsRow1.addView(btnHost)
        controlsRow1.addView(btnJoin)
        controlsRow1.addView(btnLeave)

        val controlsRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPlayPause = createGlassButton("⏸ Play")
        val btnNext = createGlassButton("⏭ Skip")
        val btnEQ = createGlassButton("🎛 EQ")
        val btn3D = createGlassButton("📍 3D: CTR") 
        controlsRow2.addView(btnPlayPause)
        controlsRow2.addView(btnNext)
        controlsRow2.addView(btnEQ)
        controlsRow2.addView(btn3D)

        val eqContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 35f; setColor(Color.argb(80, 0, 0, 0)) }
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
        }

        btn3D.setOnClickListener {
            spatialPosition = when (spatialPosition) {
                "CENTER" -> "LEFT"
                "LEFT" -> "RIGHT"
                "RIGHT" -> "REAR"
                else -> "CENTER"
            }
            val label = when(spatialPosition) {
                "CENTER" -> "CTR"
                "LEFT" -> "LFT"
                "RIGHT" -> "RGT"
                "REAR" -> "RER"
                else -> "CTR"
            }
            btn3D.text = "📍 3D: $label"
            
            player?.volume = when (spatialPosition) { 
                "LEFT" -> 0.85f 
                "RIGHT" -> 0.85f 
                "REAR" -> 0.5f 
                else -> 1.0f 
            }
            Toast.makeText(this, "Spatial placement set to $spatialPosition", Toast.LENGTH_SHORT).show()
        }

        btnEQ.setOnClickListener {
            if (eqContainer.visibility == View.VISIBLE) {
                eqContainer.visibility = View.GONE
            } else {
                val sessionId = player?.audioSessionId ?: 0
                if (sessionId != 0 && equalizer == null) {
                    setupEqualizer(sessionId)
                }

                equalizer?.let { eq ->
                    eqContainer.removeAllViews()
                    eqContainer.visibility = View.VISIBLE
                    val minEQLevel = eq.bandLevelRange[0]
                    val maxEQLevel = eq.bandLevelRange[1]
                    val seekBars = mutableListOf<SeekBar>()

                    // FEATURE 2 FIX: Added Reset EQ Button
                    val resetBtn = Button(this@MainActivity).apply {
                        text = "Reset EQ"
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply { cornerRadius = 25f; setColor(Color.parseColor("#444444")) }
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100).apply { setMargins(0, 0, 0, 30) }
                        setOnClickListener {
                            for (i in 0 until eq.numberOfBands) {
                                eq.setBandLevel(i.toShort(), 0)
                                seekBars[i].progress = 0 - minEQLevel
                            }
                        }
                    }
                    eqContainer.addView(resetBtn)

                    for (i in 0 until eq.numberOfBands) {
                        val band = i.toShort()
                        val freq = eq.getCenterFreq(band) / 1000
                        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
                        val label = TextView(this@MainActivity).apply { text = "${freq}Hz"; setTextColor(Color.WHITE); width = 140 }
                        val seekBar = SeekBar(this@MainActivity).apply {
                            max = maxEQLevel - minEQLevel
                            progress = eq.getBandLevel(band) - minEQLevel
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                    if (fromUser) eq.setBandLevel(band, (progress + minEQLevel).toShort())
                                }
                                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                            })
                        }
                        seekBars.add(seekBar)
                        row.addView(label)
                        row.addView(seekBar)
                        eqContainer.addView(row)
                    }
                } ?: Toast.makeText(this, "Start playing a song first!", Toast.LENGTH_SHORT).show()
            }
        }

        val listsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val queueTitle = TextView(this).apply { text = "Up Next"; textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 10, 0, 20) }
        val queueRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        val libraryTitle = TextView(this).apply { text = "All Songs"; textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 30, 0, 20) }
        val libraryRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f)
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        listsLayout.addView(queueTitle)
        listsLayout.addView(queueRecyclerView)
        listsLayout.addView(libraryTitle)
        listsLayout.addView(libraryRecyclerView)

        mainLayout.addView(titleText)
        mainLayout.addView(visualizerLayout)
        mainLayout.addView(controlsRow1)
        mainLayout.addView(controlsRow2)
        mainLayout.addView(eqContainer)
        mainLayout.addView(listsLayout)
        setContentView(mainLayout)

        syncManager = AudioSyncManager(this)

        queueAdapter = SongAdapter(queueSongs, true) {}
        queueRecyclerView.adapter = queueAdapter

        libraryAdapter = SongAdapter(librarySongs, false) { song ->
            val mediaItem = MediaItem.fromUri(song.uri)
            player?.addMediaItem(mediaItem)
            queueSongs.add(song)
            queueAdapter.notifyItemInserted(queueSongs.size - 1)
            if (player?.playbackState == Player.STATE_IDLE) {
                player?.prepare()
                player?.play()
            }
        }
        libraryRecyclerView.adapter = libraryAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                Collections.swap(queueSongs, from, to)
                queueAdapter.notifyItemMoved(from, to)
                player?.moveMediaItem(from, to)
                queueAdapter.notifyItemRangeChanged(0, queueSongs.size)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(queueRecyclerView)

        if (checkPermissions()) { loadLocalMusic() }

        btnPlayPause.setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        btnNext.setOnClickListener { player?.seekToNextMediaItem() }

        syncManager.onAudioReceived = { receivedUri ->
            runOnUiThread { playAudioWithPlacement(receivedUri, spatialPosition) }
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
                    onSuccess = { btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE; startHostSyncLoop() },
                    onFailure = { Toast.makeText(this, "❌ Error", Toast.LENGTH_SHORT).show() }
                )
            }
        }

        // FEATURE 1 FIX: Hide Play/Skip on Join
        btnJoin.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = false
                syncManager.startDiscovering(
                    onSuccess = { 
                        btnHost.visibility = View.GONE 
                        btnJoin.visibility = View.GONE 
                        btnLeave.visibility = View.VISIBLE
                        btnPlayPause.visibility = View.GONE // Hides Play
                        btnNext.visibility = View.GONE // Hides Skip
                    },
                    onFailure = { Toast.makeText(this, "❌ Error", Toast.LENGTH_SHORT).show() }
                )
            }
        }

        btnLeave.setOnClickListener {
            syncManager.stopAllConnections()
            player?.stop()
            syncHandler.removeCallbacks(hostSyncRunnable)
            btnHost.visibility = View.VISIBLE
            btnJoin.visibility = View.VISIBLE 
            btnLeave.visibility = View.GONE
            btnPlayPause.visibility = View.VISIBLE // Restores Play
            btnNext.visibility = View.VISIBLE // Restores Skip
        }
    }

    private fun setupEqualizer(sessionId: Int) {
        if (sessionId == 0) return
        equalizer?.release()
        try { equalizer = Equalizer(0, sessionId).apply { enabled = true } } catch (_: Exception) {}
    }

    private fun extractAverageColor(songUri: Uri): Int {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, songUri)
            val artBytes = mmr.embeddedPicture
            mmr.release()

            if (artBytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                val color = scaled.getPixel(0, 0)
                scaled.recycle()
                bitmap.recycle()

                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                hsv[1] = hsv[1].coerceAtLeast(0.4f)
                hsv[2] = hsv[2].coerceAtMost(0.4f)
                Color.HSVToColor(hsv)
            } else {
                Color.parseColor("#121212")
            }
        } catch (_: Exception) { Color.parseColor("#121212") }
    }

    private fun animateBackgroundColor(targetColor: Int) {
        val anim = ValueAnimator.ofArgb(currentBgColor, targetColor)
        anim.duration = 1000
        anim.addUpdateListener { animator ->
            val animatedColor = animator.animatedValue as Int
            mainLayoutBg.colors = intArrayOf(Color.parseColor("#0A0A0A"), animatedColor, Color.parseColor("#000000"))
        }
        anim.start()
        currentBgColor = targetColor
    }

    private fun loadLocalMusic() {
        librarySongs.clear()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ALBUM_ID)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val cursor = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val albumIdCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol)
                val albumId = it.getLong(albumIdCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                librarySongs.add(Song(uri, title, albumId))
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
        equalizer?.release()
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

