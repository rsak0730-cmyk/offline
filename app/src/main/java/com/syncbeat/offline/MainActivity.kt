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
            
            // 1. RAW BYTE EXTRACTION FOR VIDMATE MP3/M4A FILES
            try {
                val pfd = context.contentResolver.openFileDescriptor(song.uri, "r")
                if (pfd != null) {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(pfd.fileDescriptor)
                    val art = mmr.embeddedPicture
                    mmr.release()
                    pfd.close()
                    if (art != null) {
                        val options = BitmapFactory.Options()
                        options.inSampleSize = 2
                        bitmap = BitmapFactory.decodeByteArray(art, 0, art.size, options)
                    }
                }
            } catch (_: Exception) {}

            // 2. Standard Android 10+ Fallback
            if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { bitmap = context.contentResolver.loadThumbnail(song.uri, Size(300, 300), null) } catch (_: Exception) {}
            }
            
            Handler(Looper.getMainLooper()).post { callback(bitmap) }
        }.start()
    }
}

class SongAdapter(val songs: MutableList<Song>, val isQueue: Boolean, val onClick: (Song) -> Unit) : RecyclerView.Adapter<SongAdapter.VH>() {
    inner class VH(val layout: LinearLayout, val title: TextView, val artist: TextView, val albumArt: ImageView) : RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(30, 20, 30, 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
            background = GradientDrawable().apply { cornerRadius = 30f; setColor(Color.argb(if (isQueue) 50 else 20, 255, 255, 255)); setStroke(2, Color.argb(80, 0, 229, 255)) }
        }
        val albumArtView = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(110, 110).apply { setMargins(0, 0, 25, 0) }; scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#111111")) }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 20f) } }
        }
        val textLayout = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val title = TextView(parent.context).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 1 }
        val artist = TextView(parent.context).apply { setTextColor(Color.parseColor("#84FFFF")); textSize = 12f; maxLines = 1 }
        textLayout.addView(title); textLayout.addView(artist); layout.addView(albumArtView); layout.addView(textLayout)
        return VH(layout, title, artist, albumArtView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.title.text = if (isQueue) "${position + 1}. ${song.title}" else song.title
        holder.artist.text = song.artist
        holder.albumArt.setImageDrawable(null)
        ArtworkLoader.loadArtwork(holder.layout.context, song) { bitmap ->
            if (bitmap != null) holder.albumArt.setImageBitmap(bitmap) else holder.albumArt.setBackgroundColor(Color.parseColor("#111111"))
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
    
    private lateinit var seekBar: SeekBar
    private lateinit var textTimeCurrent: TextView
    private lateinit var textTimeTotal: TextView

    private val syncHandler = Handler(Looper.getMainLooper())
    private var isHostDevice = false
    private var isClientDevice = false
    private var spatialPosition = "CENTER"

    private val librarySongs = mutableListOf<Song>()
    private val queueSongs = mutableListOf<Song>()

    private lateinit var libraryAdapter: SongAdapter
    private lateinit var queueAdapter: SongAdapter

    private var currentBgColor = Color.parseColor("#041A22")
    private lateinit var mainLayoutBg: GradientDrawable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        player = ExoPlayer.Builder(this).setAudioAttributes(audioAttributes, true).build()
        syncManager = AudioSyncManager(this)

        circularVisualizer = CircularVisualizerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650).apply { setMargins(0, 20, 0, 20) }
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
                mediaItem?.localConfiguration?.uri?.let { uri -> if (isHostDevice) syncManager.broadcastAudioFile(uri) }
            }
            
            // EXOPLAYER LIVE METADATA INJECTION
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
                val artworkData = mediaMetadata.artworkData
                if (artworkData != null) {
                    val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
                    applyDynamicColors(bitmap)
                } else {
                    player?.currentMediaItem?.localConfiguration?.uri?.let { uri ->
                        val song = librarySongs.find { it.uri == uri }
                        song?.let { ArtworkLoader.loadArtwork(this@MainActivity, it) { bitmap -> applyDynamicColors(bitmap) } }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    seekBar.max = player?.duration?.toInt() ?: 0
                    textTimeTotal.text = formatTime(player?.duration ?: 0)
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                super.onAudioSessionIdChanged(audioSessionId)
                setupEqualizer(audioSessionId)
                circularVisualizer.linkToPlayer(audioSessionId)
            }
        })

        mainLayoutBg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#010B10"), currentBgColor, Color.parseColor("#000000"))).apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 1200f }
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 35, 30, 30); background = mainLayoutBg }
        val titleText = TextView(this).apply { text = "SYNCBEAT PRO ✦"; textSize = 24f; setTextColor(Color.parseColor("#00E5FF")); gravity = Gravity.CENTER; setPadding(0, 0, 0, 10); setShadowLayer(25f, 0f, 0f, Color.parseColor("#00B8D4")); typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL); letterSpacing = 0.05f }

        fun createGlassButton(buttonText: String): Button {
            return Button(this).apply {
                text = buttonText; setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); isAllCaps = true; letterSpacing = 0.05f
                layoutParams = LinearLayout.LayoutParams(0, 110, 1f).apply { setMargins(6, 0, 6, 15) }
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 35f; setColor(Color.argb(30, 255, 255, 255)); setStroke(2, Color.argb(100, 0, 229, 255)) }
            }
        }

        // TIMELINE UI
        val timelineLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10, 5, 10, 15) }
        textTimeCurrent = TextView(this).apply { text = "0:00"; setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        textTimeTotal = TextView(this).apply { text = "0:00"; setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            progressDrawable.setColorFilter(Color.parseColor("#00E5FF"), PorterDuff.Mode.SRC_ATOP)
            thumb.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        player?.seekTo(progress.toLong())
                        textTimeCurrent.text = formatTime(progress.toLong())
                        // INSTANT HOST SYNC: If host scrubs, force client devices to jump immediately
                        if (isHostDevice) {
                            syncManager.sendSyncTick(progress.toLong())
                        }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        timelineLayout.addView(textTimeCurrent); timelineLayout.addView(seekBar); timelineLayout.addView(textTimeTotal)

        val controlsRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnHost = createGlassButton("⟡ HOST")
        val btnJoin = createGlassButton("◈ JOIN")
        val btnLeave = createGlassButton("✕ LEAVE").apply { visibility = View.GONE }
        controlsRow1.addView(btnHost); controlsRow1.addView(btnJoin); controlsRow1.addView(btnLeave)

        val controlsRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPlayPause = createGlassButton("► PLAY")
        val btnNext = createGlassButton("⇥ SKIP")
        val btnEQ = createGlassButton("⎚ EQ")
        val btn3D = createGlassButton("◧ 3D: CTR")
        controlsRow2.addView(btnPlayPause); controlsRow2.addView(btnNext); controlsRow2.addView(btnEQ); controlsRow2.addView(btn3D)

        // EQ VIEW FIX: Wrapped inside a ScrollView so it doesn't break list layouts
        val eqContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = GradientDrawable().apply { cornerRadius = 30f; setColor(Color.argb(80, 0, 0, 0)) }; setPadding(25, 25, 25, 25)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) }
        }
        
        val eqScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 450) // Fixed height prevents breaking UI
        }
        val eqInnerLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        eqScrollView.addView(eqInnerLayout)
        eqContainer.addView(eqScrollView)

        btn3D.setAnimatedClick {
            spatialPosition = when (spatialPosition) { "CENTER" -> "LEFT"; "LEFT" -> "RIGHT"; "RIGHT" -> "REAR"; else -> "CENTER" }
            btn3D.text = "◧ 3D: ${spatialPosition.take(3)}"
            player?.volume = when (spatialPosition) { "LEFT", "RIGHT" -> 0.85f; "REAR" -> 0.5f; else -> 1.0f }
        }

        btnEQ.setAnimatedClick {
            if (eqContainer.visibility == View.VISIBLE) eqContainer.visibility = View.GONE else {
                val sessionId = player?.audioSessionId ?: 0
                if (sessionId != 0 && equalizer == null) setupEqualizer(sessionId)
                equalizer?.let { eq ->
                    eqInnerLayout.removeAllViews(); eqContainer.visibility = View.VISIBLE
                    val resetBtn = Button(this).apply {
                        text = "RESET EQ"; setTextColor(Color.WHITE); background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#222222")) }
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90).apply { setMargins(0, 0, 0, 15) }
                        setAnimatedClick { for (i in 0 until eq.numberOfBands) eq.setBandLevel(i.toShort(), 0) }
                    }
                    eqInnerLayout.addView(resetBtn)
                    for (i in 0 until eq.numberOfBands) {
                        val band = i.toShort(); val freq = eq.getCenterFreq(band) / 1000
                        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 8) }
                        val label = TextView(this@MainActivity).apply { text = "${freq}Hz"; setTextColor(Color.WHITE); width = 120 }
                        val sb = SeekBar(this@MainActivity).apply {
                            max = eq.bandLevelRange[1] - eq.bandLevelRange[0]; progress = eq.getBandLevel(band) - eq.bandLevelRange[0]; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) eq.setBandLevel(band, (p + eq.bandLevelRange[0]).toShort()) }
                                override fun onStartTrackingTouch(bar: SeekBar?) {}
                                override fun onStopTrackingTouch(bar: SeekBar?) {}
                            })
                        }
                        row.addView(label); row.addView(sb); eqInnerLayout.addView(row)
                    }
                }
            }
        }

        val listsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        val queueTitle = TextView(this).apply { text = "≡ UP NEXT"; setTextColor(Color.parseColor("#84FFFF")); textSize = 13f; setPadding(0, 5, 0, 5); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f }
        val queueRecyclerView = RecyclerView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); layoutManager = LinearLayoutManager(this@MainActivity) }
        val libraryTitle = TextView(this).apply { text = "▦ ALL SONGS"; setTextColor(Color.parseColor("#84FFFF")); textSize = 13f; setPadding(0, 10, 0, 5); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f }
        val libraryRecyclerView = RecyclerView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f); layoutManager = LinearLayoutManager(this@MainActivity) }
        listsLayout.addView(queueTitle); listsLayout.addView(queueRecyclerView); listsLayout.addView(libraryTitle); listsLayout.addView(libraryRecyclerView)

        mainLayout.addView(titleText); mainLayout.addView(circularVisualizer); mainLayout.addView(timelineLayout); mainLayout.addView(controlsRow1); mainLayout.addView(controlsRow2); mainLayout.addView(eqContainer); mainLayout.addView(listsLayout)
        setContentView(mainLayout)

        queueAdapter = SongAdapter(queueSongs, true) {}
        queueRecyclerView.adapter = queueAdapter

        libraryAdapter = SongAdapter(librarySongs, false) { song ->
            player?.addMediaItem(MediaItem.fromUri(song.uri))
            queueSongs.add(song); queueAdapter.notifyItemInserted(queueSongs.size - 1)
            if (player?.playbackState == Player.STATE_IDLE) player?.prepare()
            player?.play(); btnPlayPause.text = "❚❚ PAUSE"
            player?.audioSessionId?.let { if (it != 0) circularVisualizer.linkToPlayer(it) }
        }
        libraryRecyclerView.adapter = libraryAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition; val to = target.adapterPosition
                Collections.swap(queueSongs, from, to); queueAdapter.notifyItemMoved(from, to)
                player?.moveMediaItem(from, to); queueAdapter.notifyItemRangeChanged(0, queueSongs.size)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(queueRecyclerView)

        if (checkPermissions()) loadLocalMusic()

        btnPlayPause.setAnimatedClick { 
            player?.let { 
                if (it.isPlaying) { it.pause(); btnPlayPause.text = "► PLAY" } 
                else { it.play(); btnPlayPause.text = "❚❚ PAUSE"; if (it.audioSessionId != 0) circularVisualizer.linkToPlayer(it.audioSessionId) } 
            } 
        }
        btnNext.setAnimatedClick { player?.seekToNextMediaItem() }

        syncManager.onAudioReceived = { receivedUri -> runOnUiThread { playAudioWithPlacement(receivedUri, spatialPosition) } }
        
        syncManager.onSyncTickReceived = { hostPositionMs ->
            if (isClientDevice) {
                player?.let { localPlayer ->
                    // Tight 100ms threshold ensures playback jumps instantly when host scrubs timeline
                    val drift = kotlin.math.abs(localPlayer.currentPosition - hostPositionMs)
                    if (drift > 100) localPlayer.seekTo(hostPositionMs) 
                }
            }
        }

        btnHost.setAnimatedClick {
            if (checkPermissions()) {
                isHostDevice = true; isClientDevice = false; seekBar.isEnabled = true // Host CAN control timeline
                syncManager.startHosting("HostDevice",
                    onSuccess = { btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE; startHostSyncLoop() },
                    onFailure = { Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show() }
                )
            }
        }

        btnJoin.setAnimatedClick {
            if (checkPermissions()) {
                isHostDevice = false; isClientDevice = true; seekBar.isEnabled = false // Clients CANNOT control timeline
                syncManager.startDiscovering(
                    onSuccess = { btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE; btnPlayPause.visibility = View.GONE; btnNext.visibility = View.GONE },
                    onFailure = { Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show() }
                )
            }
        }

        btnLeave.setAnimatedClick {
            syncManager.stopAllConnections(); player?.stop(); isHostDevice = false; isClientDevice = false; seekBar.isEnabled = true
            syncHandler.removeCallbacks(hostSyncRunnable); syncHandler.removeCallbacks(progressUpdateRunnable)
            btnHost.visibility = View.VISIBLE; btnJoin.visibility = View.VISIBLE; btnLeave.visibility = View.GONE
            btnPlayPause.visibility = View.VISIBLE; btnNext.visibility = View.VISIBLE
        }
        
        syncHandler.post(progressUpdateRunnable)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            player?.let {
                if (it.isPlaying) {
                    seekBar.progress = it.currentPosition.toInt()
                    textTimeCurrent.text = formatTime(it.currentPosition)
                }
            }
            syncHandler.postDelayed(this, 500)
        }
    }

    private fun applyDynamicColors(bitmap: Bitmap?) {
        if (bitmap == null) return
        Thread {
            try {
                val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                val baseColor = scaled.getPixel(0, 0)
                scaled.recycle()
                
                val hsv = FloatArray(3)
                Color.colorToHSV(baseColor, hsv)
                if (hsv[1] < 0.2f) hsv[1] = 0.5f 
                
                val neonColor = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtLeast(0.7f), 1.0f))
                val bgColor = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtLeast(0.5f), 0.15f))
                
                runOnUiThread {
                    animateBackgroundColor(bgColor)
                    circularVisualizer.updateThemeColor(neonColor)
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun setupEqualizer(sessionId: Int) {
        if (sessionId == 0) return
        equalizer?.release()
        try { equalizer = Equalizer(0, sessionId).apply { enabled = true } } catch (_: Exception) {}
    }

    private fun animateBackgroundColor(targetColor: Int) {
        val anim = ValueAnimator.ofArgb(currentBgColor, targetColor)
        anim.duration = 1000
        anim.addUpdateListener { animator ->
            val animatedColor = animator.animatedValue as Int
            mainLayoutBg.colors = intArrayOf(Color.parseColor("#010B10"), animatedColor, Color.parseColor("#000000"))
        }
        anim.start()
        currentBgColor = targetColor
    }

    private fun loadLocalMusic() {
        librarySongs.clear()
        val cursor = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, MediaStore.Audio.Media.DATE_ADDED + " DESC")
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val albumIdCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID); val durCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (it.moveToNext()) {
                var title = it.getString(titleCol)?.replace("_", " ") ?: "Unknown"; var artist = it.getString(artistCol)
                if (artist.isNullOrBlank() || artist.equals("<unknown>", true)) { artist = if (title.contains("-")) title.split("-")[0].trim() else "Vidmate Download" }
                librarySongs.add(Song(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(idCol)), title, artist, it.getLong(albumIdCol), it.getLong(durCol)))
            }
        }
        libraryAdapter.notifyDataSetChanged()
    }

    private fun playAudioWithPlacement(uri: Uri, position: String) {
        player?.stop(); player?.setMediaItem(MediaItem.fromUri(uri)); player?.prepare()
        player?.volume = when (position) { "LEFT", "RIGHT" -> 0.85f; "REAR" -> 0.5f; else -> 1.0f }; player?.play()
    }

    private val hostSyncRunnable = object : Runnable {
        override fun run() {
            if (isHostDevice && player?.isPlaying == true) player?.currentPosition?.let { syncManager.sendSyncTick(it) }
            syncHandler.postDelayed(this, 1000)
        }
    }

    private fun startHostSyncLoop() { syncHandler.removeCallbacks(hostSyncRunnable); syncHandler.post(hostSyncRunnable) }

    override fun onDestroy() { super.onDestroy(); syncHandler.removeCallbacks(hostSyncRunnable); syncHandler.removeCallbacks(progressUpdateRunnable); equalizer?.release(); circularVisualizer.release(); player?.release() }

    private fun checkPermissions(): Boolean {
        val req = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { req.add(Manifest.permission.NEARBY_WIFI_DEVICES); req.add(Manifest.permission.READ_MEDIA_AUDIO) } else { req.add(Manifest.permission.READ_EXTERNAL_STORAGE) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { req.add(Manifest.permission.BLUETOOTH_ADVERTISE); req.add(Manifest.permission.BLUETOOTH_CONNECT); req.add(Manifest.permission.BLUETOOTH_SCAN) }
        val missing = req.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1); return false }
        return true
    }
}
