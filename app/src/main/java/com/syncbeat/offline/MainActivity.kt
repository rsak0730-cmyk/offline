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
import kotlin.math.PI
import kotlin.math.sin

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
            try {
                val pfd = context.contentResolver.openFileDescriptor(song.uri, "r")
                if (pfd != null) {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(pfd.fileDescriptor)
                    val art = mmr.embeddedPicture
                    mmr.release(); pfd.close()
                    if (art != null) {
                        val options = BitmapFactory.Options(); options.inSampleSize = 2
                        bitmap = BitmapFactory.decodeByteArray(art, 0, art.size, options)
                    }
                }
            } catch (_: Exception) {}

            if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { bitmap = context.contentResolver.loadThumbnail(song.uri, Size(300, 300), null) } catch (_: Exception) {}
            }
            if (bitmap == null && song.albumId > 0) {
                try {
                    val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
                    val stream = context.contentResolver.openInputStream(albumArtUri)
                    if (stream != null) { bitmap = BitmapFactory.decodeStream(stream); stream.close() }
                } catch (_: Exception) {}
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
    
    private lateinit var seekBar: SeekBar
    private lateinit var textTimeCurrent: TextView
    private lateinit var textTimeTotal: TextView
    private var isDraggingTimeline = false

    private val syncHandler = Handler(Looper.getMainLooper())
    private var isHostDevice = false
    private var isClientDevice = false
    
    private var spatialPosition = "OFF"

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
        
        // Initialize the new UDP AudioSyncManager for ExoPlayer
        syncManager = AudioSyncManager(player!!) { trackUriString ->
            runOnUiThread {
                player?.setMediaItem(MediaItem.fromUri(Uri.parse(trackUriString)))
                player?.prepare()
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
                            
                            // If auto-transitioning, host tells clients to change track too
                            if (isHostDevice && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                                syncManager.hostNextTrack(playingUri.toString())
                            }
                        }
                    }
                }
            }
            
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

        val timelineLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10, 40, 10, 25) }
        textTimeCurrent = TextView(this).apply { text = "0:00"; setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        textTimeTotal = TextView(this).apply { text = "0:00"; setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            progressDrawable.setColorFilter(Color.parseColor("#00E5FF"), PorterDuff.Mode.SRC_ATOP)
            thumb.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { textTimeCurrent.text = formatTime(progress.toLong()) }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { isDraggingTimeline = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    isDraggingTimeline = false
                    bar?.let {
                        if (isHostDevice) {
                            syncManager.hostSeekTo(it.progress.toLong())
                        } else if (!isClientDevice) {
                            player?.seekTo(it.progress.toLong())
                        }
                    }
                }
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
        val btn3D = createGlassButton("◧ 8D OFF")
        controlsRow2.addView(btnPlayPause); controlsRow2.addView(btnNext); controlsRow2.addView(btnEQ); controlsRow2.addView(btn3D)

        btn3D.setAnimatedClick {
            spatialPosition = when (spatialPosition) { 
                "OFF" -> "FRONT"
                "FRONT" -> "RIGHT"
                "RIGHT" -> "REAR"
                "REAR" -> "LEFT"
                else -> "OFF" 
            }
            btn3D.text = if (spatialPosition == "OFF") "◧ 8D OFF" else "◧ $spatialPosition"
            Toast.makeText(this, if (spatialPosition == "OFF") "8D Effect Disabled" else "8D Position: $spatialPosition", Toast.LENGTH_SHORT).show()
            if (spatialPosition == "OFF") player?.volume = 1.0f
        }

        btnEQ.setAnimatedClick { showEqDialog() }

        val listsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        val queueTitle = TextView(this).apply { text = "≡ UP NEXT"; setTextColor(Color.parseColor("#84FFFF")); textSize = 13f; setPadding(0, 5, 0, 5); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f }
        val queueRecyclerView = RecyclerView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); layoutManager = LinearLayoutManager(this@MainActivity) }
        val libraryTitle = TextView(this).apply { text = "▦ ALL SONGS"; setTextColor(Color.parseColor("#84FFFF")); textSize = 13f; setPadding(0, 10, 0, 5); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f }
        val libraryRecyclerView = RecyclerView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f); layoutManager = LinearLayoutManager(this@MainActivity) }
        listsLayout.addView(queueTitle); listsLayout.addView(queueRecyclerView); listsLayout.addView(libraryTitle); listsLayout.addView(libraryRecyclerView)

        mainLayout.addView(titleText); mainLayout.addView(timelineLayout); mainLayout.addView(controlsRow1); mainLayout.addView(controlsRow2); mainLayout.addView(listsLayout)
        setContentView(mainLayout)

        queueAdapter = SongAdapter(queueSongs, true) {}
        queueRecyclerView.adapter = queueAdapter

        libraryAdapter = SongAdapter(librarySongs, false) { song ->
            if (isClientDevice) return@SongAdapter // Clients shouldn't trigger track loads manually
            
            player?.addMediaItem(MediaItem.fromUri(song.uri))
            queueSongs.add(song); queueAdapter.notifyItemInserted(queueSongs.size - 1)
            if (player?.playbackState == Player.STATE_IDLE) player?.prepare()
            
            if (isHostDevice) {
                syncManager.hostNextTrack(song.uri.toString())
            } else {
                player?.play() 
            }
            btnPlayPause.text = "❚❚ PAUSE"
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
                if (isHostDevice) {
                    if (it.isPlaying) {
                        syncManager.hostPause()
                        btnPlayPause.text = "► PLAY"
                    } else {
                        syncManager.hostPlay()
                        btnPlayPause.text = "❚❚ PAUSE"
                    }
                } else if (!isClientDevice) {
                    if (it.isPlaying) { it.pause(); btnPlayPause.text = "► PLAY" } 
                    else { it.play(); btnPlayPause.text = "❚❚ PAUSE" }
                }
            } 
        }
        
        btnNext.setAnimatedClick { 
            if (isHostDevice) {
                player?.seekToNextMediaItem()
                // Wait for the transition listener to handle the UDP network broadcast
            } else if (!isClientDevice) {
                player?.seekToNextMediaItem()
            }
        }

        btnHost.setAnimatedClick {
            if (checkPermissions()) {
                isHostDevice = true; isClientDevice = false; seekBar.isEnabled = true
                
                // Initialize as Host
                syncManager.initialize(isHost = true)
                
                btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE
            }
        }

        btnJoin.setAnimatedClick {
            if (checkPermissions()) {
                isHostDevice = false; isClientDevice = true; seekBar.isEnabled = false
                
                // Note: Connect the host and client to the same Mobile Wi-Fi Hotspot. 
                // 192.168.43.1 is the standard Gateway IP for an Android Hotspot host.
                syncManager.initialize(isHost = false, hostIp = "192.168.43.1")
                
                btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE 
                btnPlayPause.visibility = View.GONE; btnNext.visibility = View.GONE
            }
        }

        btnLeave.setAnimatedClick {
            syncManager.release(); player?.stop(); isHostDevice = false; isClientDevice = false; seekBar.isEnabled = true
            btnHost.visibility = View.VISIBLE; btnJoin.visibility = View.VISIBLE; btnLeave.visibility = View.GONE
            btnPlayPause.visibility = View.VISIBLE; btnNext.visibility = View.VISIBLE
        }
        
        syncHandler.post(progressUpdateRunnable)
        syncHandler.post(spatialPanRunnable)
    }

    private fun showEqDialog() {
        val sessionId = player?.audioSessionId ?: 0
        if (sessionId == 0) { Toast.makeText(this, "Play a song first!", Toast.LENGTH_SHORT).show(); return }
        if (equalizer == null) setupEqualizer(sessionId)
        val eq = equalizer ?: return

        val dialogView = ScrollView(this).apply { setPadding(40, 40, 40, 40) }
        val eqLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialogView.addView(eqLayout)

        val resetBtn = Button(this).apply {
            text = "RESET EQ"; setTextColor(Color.WHITE); background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#222222")) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100).apply { setMargins(0, 0, 0, 30) }
        }
        eqLayout.addView(resetBtn)

        val seekBars = mutableListOf<SeekBar>()
        for (i in 0 until eq.numberOfBands) {
            val band = i.toShort(); val freq = eq.getCenterFreq(band) / 1000
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 15, 0, 15) }
            val label = TextView(this).apply { text = "${freq}Hz"; setTextColor(Color.BLACK); width = 140; textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
            val sb = SeekBar(this).apply {
                max = eq.bandLevelRange[1] - eq.bandLevelRange[0]; progress = eq.getBandLevel(band) - eq.bandLevelRange[0]
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                progressDrawable.setColorFilter(Color.parseColor("#00E5FF"), PorterDuff.Mode.SRC_ATOP)
                thumb.setColorFilter(Color.parseColor("#00B8D4"), PorterDuff.Mode.SRC_ATOP)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) eq.setBandLevel(band, (p + eq.bandLevelRange[0]).toShort()) }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
            seekBars.add(sb); row.addView(label); row.addView(sb); eqLayout.addView(row)
        }

        resetBtn.setAnimatedClick {
            for (i in 0 until eq.numberOfBands) { eq.setBandLevel(i.toShort(), 0); seekBars[i].progress = 0 - eq.bandLevelRange[0] }
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle("Equalizer").setView(dialogView).setPositiveButton("Close", null).show()
    }

    private fun formatTime(ms: Long): String { val totalSeconds = ms / 1000; return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60) }

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            player?.let {
                if (it.isPlaying && !isDraggingTimeline) { 
                    val dur = it.duration
                    if (dur > 0 && seekBar.max != dur.toInt()) {
                        seekBar.max = dur.toInt()
                        textTimeTotal.text = formatTime(dur)
                    }
                    seekBar.progress = it.currentPosition.toInt()
                    textTimeCurrent.text = formatTime(it.currentPosition) 
                }
            }
            syncHandler.postDelayed(this, 500)
        }
    }

    private val spatialPanRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.isPlaying) {
                    val phase = when (spatialPosition) {
                        "FRONT" -> 0.0
                        "RIGHT" -> PI / 2.0
                        "REAR" -> PI
                        "LEFT" -> 3.0 * PI / 2.0
                        else -> null
                    }
                    if (phase != null) {
                        val t = (p.currentPosition % 8000.0) / 8000.0 * 2.0 * PI
                        p.volume = (0.575f + 0.425f * sin(t + phase)).toFloat()
                    }
                }
            }
            syncHandler.postDelayed(this, 50)
        }
    }

    private fun applyDynamicColors(bitmap: Bitmap?) {
        if (bitmap == null) return
        Thread {
            try {
                val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                val baseColor = scaled.getPixel(0, 0); scaled.recycle()
                val hsv = FloatArray(3); Color.colorToHSV(baseColor, hsv)
                if (hsv[1] < 0.2f) hsv[1] = 0.5f 
                
                val bgColor = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtLeast(0.5f), 0.15f))
                runOnUiThread { animateBackgroundColor(bgColor) }
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
        anim.start(); currentBgColor = targetColor
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

    override fun onDestroy() { super.onDestroy(); syncManager.release(); syncHandler.removeCallbacks(progressUpdateRunnable); syncHandler.removeCallbacks(spatialPanRunnable); equalizer?.release(); player?.release() }

    private fun checkPermissions(): Boolean {
        val req = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { req.add(Manifest.permission.NEARBY_WIFI_DEVICES); req.add(Manifest.permission.READ_MEDIA_AUDIO) } else { req.add(Manifest.permission.READ_EXTERNAL_STORAGE) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { req.add(Manifest.permission.BLUETOOTH_ADVERTISE); req.add(Manifest.permission.BLUETOOTH_CONNECT); req.add(Manifest.permission.BLUETOOTH_SCAN) }
        val missing = req.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1); return false }
        return true
    }
}
