package com.syncbeat.offline

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

data class Song(val uri: Uri, val title: String, val artist: String, val albumId: Long, val duration: Long)

@SuppressLint("ClickableViewAccessibility")
fun View.setAnimatedClick(onClick: () -> Unit) {
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                if (event.action == MotionEvent.ACTION_UP) onClick()
            }
        }
        true
    }
}

object ArtworkLoader {
    fun loadArtwork(context: Context, song: Song, callback: (Bitmap?) -> Unit) {
        Thread {
            var bitmap: Bitmap? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { bitmap = context.contentResolver.loadThumbnail(song.uri, Size(300, 300), null) } catch (_: Exception) {}
            }
            if (bitmap == null) {
                try {
                    val mmr = MediaMetadataRetriever().apply { setDataSource(context, song.uri) }
                    val art = mmr.embeddedPicture
                    mmr.release()
                    if (art != null) bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                } catch (_: Exception) {}
            }
            if (bitmap == null && song.albumId > 0) {
                try {
                    val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
                    val stream = context.contentResolver.openInputStream(albumArtUri)
                    bitmap = BitmapFactory.decodeStream(stream)
                    stream?.close()
                } catch (_: Exception) {}
            }
            Handler(Looper.getMainLooper()).post { callback(bitmap) }
        }.start()
    }
}

class SongAdapter(
    val songs: MutableList<Song>,
    val isQueue: Boolean,
    val onClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.VH>() {

    inner class VH(val layout: LinearLayout, val title: TextView, val artist: TextView, val albumArt: ImageView) : RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 20, 30, 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.argb(if (isQueue) 50 else 20, 255, 255, 255))
                setStroke(2, Color.argb(100, 251, 191, 36))
            }
        }

        val albumArtView = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(110, 110).apply { setMargins(0, 0, 25, 0) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#222222")) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 20f) }
            }
        }

        val textLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(parent.context).apply {
            setTextColor(Color.WHITE); textSize = 15f; maxLines = 1
        }
        val artist = TextView(parent.context).apply {
            setTextColor(Color.parseColor("#FDE68A")); textSize = 12f; maxLines = 1
        }

        textLayout.addView(title)
        textLayout.addView(artist)
        layout.addView(albumArtView)
        layout.addView(textLayout)
        return VH(layout, title, artist, albumArtView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.title.text = if (isQueue) "${position + 1}. ${song.title}" else song.title
        holder.artist.text = song.artist
        holder.albumArt.setImageDrawable(null)

        ArtworkLoader.loadArtwork(holder.layout.context, song) { bitmap ->
            if (bitmap != null) holder.albumArt.setImageBitmap(bitmap)
            else holder.albumArt.setBackgroundColor(Color.parseColor("#222222"))
        }

        holder.layout.setAnimatedClick { onClick(song) }
    }

    override fun getItemCount() = songs.size
}

class MainActivity : ComponentActivity() {

    private lateinit var syncManager: AudioSyncManager
    private var player: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private lateinit var circularVisualizer: CircularVisualizerView

    private val syncHandler = Handler(Looper.getMainLooper())
    private var isHostDevice = false
    private var isClientDevice = false
    private var spatialPosition = "CENTER"

    private val librarySongs = mutableListOf<Song>()
    private val queueSongs = mutableListOf<Song>()

    private lateinit var libraryAdapter: SongAdapter
    private lateinit var queueAdapter: SongAdapter

    private var currentBgColor = Color.parseColor("#4A3B0F")
    private lateinit var mainLayoutBg: GradientDrawable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .build()
            
        syncManager = AudioSyncManager(this)

        circularVisualizer = CircularVisualizerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650).apply {
                setMargins(0, 20, 0, 20)
            }
        }

        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
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
                    val song = librarySongs.find { it.uri == uri }
                    song?.let {
                        val newColor = extractAverageColor(it.uri)
                        animateBackgroundColor(newColor)
                    }
                    if (isHostDevice) syncManager.broadcastAudioFile(uri)
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                super.onAudioSessionIdChanged(audioSessionId)
                setupEqualizer(audioSessionId)
                circularVisualizer.linkToPlayer(audioSessionId)
            }
        })

        mainLayoutBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1C1404"), currentBgColor, Color.parseColor("#080602"))
        ).apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 1200f }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 35, 30, 30)
            background = mainLayoutBg
        }

        val titleText = TextView(this).apply {
            text = "SyncBeat Pro ✨"
            textSize = 26f
            setTextColor(Color.parseColor("#FBBF24"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
            setShadowLayer(25f, 0f, 0f, Color.parseColor("#D97706"))
            typeface = Typeface.DEFAULT_BOLD
        }

        fun createGlassButton(buttonText: String): Button {
            return Button(this).apply {
                text = buttonText
                setTextColor(Color.WHITE)
                textSize = 12f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, 110, 1f).apply { setMargins(6, 0, 6, 15) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 35f
                    setColor(Color.argb(45, 255, 255, 255))
                    setStroke(2, Color.argb(150, 251, 191, 36))
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
        val btnPlayPause = createGlassButton("▶ Play")
        val btnNext = createGlassButton("⏭ Skip")
        val btnEQ = createGlassButton("🎛️ EQ")
        val btn3D = createGlassButton("📍 3D: CTR")
        controlsRow2.addView(btnPlayPause)
        controlsRow2.addView(btnNext)
        controlsRow2.addView(btnEQ)
        controlsRow2.addView(btn3D)

        val eqContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 30f; setColor(Color.argb(80, 0, 0, 0)) }
            setPadding(25, 25, 25, 25)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) }
        }

        btn3D.setAnimatedClick {
            spatialPosition = when (spatialPosition) {
                "CENTER" -> "LEFT"
                "LEFT" -> "RIGHT"
                "RIGHT" -> "REAR"
                else -> "CENTER"
            }
            btn3D.text = "📍 3D: ${spatialPosition.take(3)}"
            player?.volume = when (spatialPosition) {
                "LEFT", "RIGHT" -> 0.85f
                "REAR" -> 0.5f
                else -> 1.0f
            }
            Toast.makeText(this, "Spatial placement set to $spatialPosition", Toast.LENGTH_SHORT).show()
        }

        btnEQ.setAnimatedClick {
            if (eqContainer.visibility == View.VISIBLE) {
                eqContainer.visibility = View.GONE
            } else {
                val sessionId = player?.audioSessionId ?: 0
                if (sessionId != 0 && equalizer == null) setupEqualizer(sessionId)

                equalizer?.let { eq ->
                    eqContainer.removeAllViews()
                    eqContainer.visibility = View.VISIBLE
                    val minEQLevel = eq.bandLevelRange[0]
                    val maxEQLevel = eq.bandLevelRange[1]
                    val seekBars = mutableListOf<SeekBar>()

                    val resetBtn = Button(this).apply {
                        text = "Reset EQ"
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#444444")) }
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90).apply { setMargins(0, 0, 0, 15) }
                        setAnimatedClick {
                            for (i in 0 until eq.numberOfBands) {
                                eq.setBandLevel(i.toShort(), 0)
                                seekBars[i].progress = 0 - minEQLevel
                            }
                            Toast.makeText(this@MainActivity, "EQ Reset", Toast.LENGTH_SHORT).show()
                        }
                    }
                    eqContainer.addView(resetBtn)

                    for (i in 0 until eq.numberOfBands) {
                        val band = i.toShort()
                        val freq = eq.getCenterFreq(band) / 1000
                        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 8) }
                        val label = TextView(this@MainActivity).apply { text = "${freq}Hz"; setTextColor(Color.WHITE); width = 120 }
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

        val queueTitle = TextView(this).apply { text = "📜 Up Next"; setTextColor(Color.parseColor("#FDE68A")); textSize = 14f; setPadding(0, 5, 0, 5) }
        val queueRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        val libraryTitle = TextView(this).apply { text = "🎵 All Songs"; setTextColor(Color.parseColor("#FDE68A")); textSize = 14f; setPadding(0, 10, 0, 5) }
        val libraryRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f)
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        listsLayout.addView(queueTitle)
        listsLayout.addView(queueRecyclerView)
        listsLayout.addView(libraryTitle)
        listsLayout.addView(libraryRecyclerView)

        mainLayout.addView(titleText)
        mainLayout.addView(circularVisualizer) 
        mainLayout.addView(controlsRow1)
        mainLayout.addView(controlsRow2)
        mainLayout.addView(eqContainer)
        mainLayout.addView(listsLayout)
        setContentView(mainLayout)

        queueAdapter = SongAdapter(queueSongs, true) {}
        queueRecyclerView.adapter = queueAdapter

        libraryAdapter = SongAdapter(librarySongs, false) { song ->
            val mediaItem = MediaItem.fromUri(song.uri)
            player?.addMediaItem(mediaItem)
            queueSongs.add(song)
            queueAdapter.notifyItemInserted(queueSongs.size - 1)
            
            if (player?.playbackState == Player.STATE_IDLE) {
                player?.prepare()
            }
            player?.play()
            btnPlayPause.text = "⏸ Pause"
            
            player?.audioSessionId?.let { sessionId ->
                if (sessionId != 0) circularVisualizer.linkToPlayer(sessionId)
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

        btnPlayPause.setAnimatedClick { 
            player?.let { 
                if (it.isPlaying) {
                    it.pause()
                    btnPlayPause.text = "▶ Play"
                } else {
                    it.play()
                    btnPlayPause.text = "⏸ Pause"
                    if (it.audioSessionId != 0) {
                        circularVisualizer.linkToPlayer(it.audioSessionId)
                    }
                } 
            } 
        }
        btnNext.setAnimatedClick { player?.seekToNextMediaItem() }

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

        btnHost.setAnimatedClick {
            if (checkPermissions()) {
                isHostDevice = true; isClientDevice = false
                syncManager.startHosting("HostDevice",
                    onSuccess = { btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE; startHostSyncLoop() },
                    onFailure = { Toast.makeText(this, "❌ Error", Toast.LENGTH_SHORT).show() }
                )
            }
        }

        btnJoin.setAnimatedClick {
            if (checkPermissions()) {
                isHostDevice = false; isClientDevice = true
                syncManager.startDiscovering(
                    onSuccess = {
                        btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE
                        btnPlayPause.visibility = View.GONE; btnNext.visibility = View.GONE
                    },
                    onFailure = { Toast.makeText(this, "❌ Error", Toast.LENGTH_SHORT).show() }
                )
            }
        }

        btnLeave.setAnimatedClick {
            syncManager.stopAllConnections()
            player?.stop()
            isHostDevice = false; isClientDevice = false
            syncHandler.removeCallbacks(hostSyncRunnable)
            btnHost.visibility = View.VISIBLE; btnJoin.visibility = View.VISIBLE; btnLeave.visibility = View.GONE
            btnPlayPause.visibility = View.VISIBLE; btnNext.visibility = View.VISIBLE
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
                val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                val color = scaled.getPixel(0, 0)
                scaled.recycle(); bitmap.recycle()

                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                hsv[1] = hsv[1].coerceAtLeast(0.5f)
                hsv[2] = hsv[2].coerceAtMost(0.35f)
                Color.HSVToColor(hsv)
            } else { Color.parseColor("#4A3B0F") }
        } catch (_: Exception) { Color.parseColor("#4A3B0F") }
    }

    private fun animateBackgroundColor(targetColor: Int) {
        val anim = ValueAnimator.ofArgb(currentBgColor, targetColor)
        anim.duration = 1000
        anim.addUpdateListener { animator ->
            val animatedColor = animator.animatedValue as Int
            mainLayoutBg.colors = intArrayOf(Color.parseColor("#1C1404"), animatedColor, Color.parseColor("#080602"))
        }
        anim.start()
        currentBgColor = targetColor
    }

    private fun loadLocalMusic() {
        librarySongs.clear()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val cursor = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                var title = it.getString(titleCol) ?: "Unknown"
                title = title.replace("_", " ") 
                var artist = it.getString(artistCol)
                val albumId = it.getLong(albumIdCol)
                val dur = it.getLong(durCol)

                if (artist.isNullOrBlank() || artist.equals("<unknown>", ignoreCase = true)) {
                    if (title.contains("-")) {
                        val parts = title.split("-")
                        artist = parts[0].trim()
                        title = parts.subList(1, parts.size).joinToString("-").trim()
                    } else if (title.contains("_")) {
                        val parts = title.split("_")
                        artist = parts[0].trim()
                    } else {
                        artist = "Vidmate Download"
                    }
                }

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                librarySongs.add(Song(uri, title, artist, albumId, dur))
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
        circularVisualizer.release() 
        player?.release()
    }

    private fun checkPermissions(): Boolean {
        val requiredPermissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
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

