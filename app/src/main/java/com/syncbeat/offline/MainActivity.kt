package com.syncbeat.offline

import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

// Data Model
data class Song(val uri: Uri, val title: String, val artist: String, val albumId: Long, val duration: Long)

// Vidmate/Social Media Thumbnail Extractor
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

// Glassmorphism Track List Adapter
class TrackAdapter(val songs: MutableList<Song>, val onClick: (Song) -> Unit) : RecyclerView.Adapter<TrackAdapter.VH>() {
    inner class VH(val layout: LinearLayout, val title: TextView, val artist: TextView, val albumArt: ImageView) : RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 25, 40, 25)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 15)
            }
            // Liquid Glassy Background for List Items
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 35f
                setColor(Color.argb(25, 255, 255, 255))
                setStroke(2, Color.argb(80, 251, 191, 36)) // Golden transparent stroke
            }
        }

        val albumArtView = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 40, 0) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 25f; setColor(Color.parseColor("#1C1404")) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 25f) }
            }
        }

        val textLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(parent.context).apply {
            setTextColor(Color.WHITE); textSize = 16f; maxLines = 1
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val artist = TextView(parent.context).apply {
            setTextColor(Color.parseColor("#FDE68A")); textSize = 13f; maxLines = 1 // Golden text
        }
        
        val menuIcon = TextView(parent.context).apply {
            text = "⋮"
            textSize = 24f
            setTextColor(Color.parseColor("#FBBF24"))
            setPadding(20, 0, 0, 0)
        }

        textLayout.addView(title)
        textLayout.addView(artist)
        layout.addView(albumArtView)
        layout.addView(textLayout)
        layout.addView(menuIcon)

        return VH(layout, title, artist, albumArtView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.artist.text = song.artist
        holder.albumArt.setImageDrawable(null)

        ArtworkLoader.loadArtwork(holder.layout.context, song) { bitmap ->
            if (bitmap != null) holder.albumArt.setImageBitmap(bitmap)
            else holder.albumArt.setBackgroundColor(Color.parseColor("#1C1404"))
        }
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
    private lateinit var trackAdapter: TrackAdapter

    // UIs
    private lateinit var libraryScreen: FrameLayout
    private lateinit var fullPlayerScreen: LinearLayout
    private lateinit var syncLabScreen: LinearLayout

    // Mini Player
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var btnMiniPlayPause: TextView

    // Full Player
    private lateinit var mainAlbumArt: ImageView
    private lateinit var mainTitle: TextView
    private lateinit var mainArtist: TextView
    private lateinit var btnPlayPauseFull: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var fullPlayerBg: GradientDrawable
    private var currentBgColor = Color.parseColor("#1C1404") 

    // Glass Button Factory
    private fun createGlassButton(title: String): Button {
        return Button(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 12f; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, 110, 1f).apply { setMargins(10, 10, 10, 20) }
            background = GradientDrawable().apply { 
                cornerRadius = 35f
                setColor(Color.argb(40, 255, 255, 255)) 
                setStroke(2, Color.argb(150, 251, 191, 36)) // Golden glowing edge
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        syncManager = AudioSyncManager(this)

        // Golden Liquid Root Background
        val rootBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1C1404"), Color.parseColor("#080602"))
        ).apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 1400f }
        
        val rootLayout = FrameLayout(this).apply { background = rootBg }

        // ================= 1. LIBRARY SCREEN (SAMSUNG LAYOUT + GOLDEN THEME) =================
        libraryScreen = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val libraryVBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(20, 0, 20, 0)
        }

        val topAppBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 50, 20, 20)
        }
        val appTitle = TextView(this).apply {
            text = "SyncBeat Pro" // Changed text back to golden theme app name
            textSize = 24f
            setTextColor(Color.parseColor("#FBBF24")) // Golden App Title
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setShadowLayer(15f, 0f, 0f, Color.parseColor("#D97706"))
        }
        val searchIcon = TextView(this).apply { text = "🔍"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0, 0, 40, 0) }
        val menuIcon = TextView(this).apply { text = "⋮"; textSize = 24f; setTextColor(Color.WHITE) }
        topAppBar.addView(appTitle); topAppBar.addView(searchIcon); topAppBar.addView(menuIcon)

        val tabsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 10, 0, 10) }
        val tabsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabs = listOf("Spotify", "Favourites", "Playlists", "Tracks", "Albums", "Artists", "Folders")
        tabs.forEach { tabName ->
            val isSelected = tabName == "Tracks"
            val tabText = TextView(this).apply {
                text = tabName; textSize = 16f
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#A08A4D"))
                typeface = if (isSelected) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
                setPadding(30, 10, 30, 20)
            }
            tabsLayout.addView(tabText)
        }
        tabsScroll.addView(tabsLayout)

        val trackHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 20, 20, 20)
        }
        val sortIcon = TextView(this).apply { text = "≡ Date added"; textSize = 14f; setTextColor(Color.parseColor("#FDE68A")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val shuffleIcon = TextView(this).apply { text = "🔀"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0,0,40,0) }
        val playAllIcon = TextView(this).apply { text = "▶"; textSize = 20f; setTextColor(Color.WHITE) }
        trackHeader.addView(sortIcon); trackHeader.addView(shuffleIcon); trackHeader.addView(playAllIcon)

        val libraryRecyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        libraryVBox.addView(topAppBar); libraryVBox.addView(tabsScroll); libraryVBox.addView(trackHeader); libraryVBox.addView(libraryRecyclerView)

        // Glassy Mini Player
        miniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 20, 40, 20)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160).apply {
                gravity = Gravity.BOTTOM; setMargins(20, 0, 20, 40)
            }
            background = GradientDrawable().apply {
                setColor(Color.argb(80, 10, 10, 10)) 
                cornerRadius = 75f
                setStroke(3, Color.parseColor("#FBBF24")) // Solid gold stroke for glass pill
            }
            setOnClickListener { libraryScreen.visibility = View.GONE; fullPlayerScreen.visibility = View.VISIBLE }
        }

        val miniIcon = TextView(this).apply { text = "🎵"; textSize = 24f; setPadding(0, 0, 30, 0) }
        miniTitle = TextView(this).apply {
            text = "No queued tracks"
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            ellipsize = TextUtils.TruncateAt.MARQUEE; isSelected = true; isSingleLine = true
        }
        val btnMiniPrev = TextView(this).apply { text = "⏮"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0,0,30,0); setOnClickListener { player?.seekToPreviousMediaItem() } }
        btnMiniPlayPause = TextView(this).apply { text = "▶"; textSize = 20f; setTextColor(Color.parseColor("#FBBF24")); setPadding(0,0,30,0); setOnClickListener { togglePlayPause() } }
        val btnMiniNext = TextView(this).apply { text = "⏭"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0,0,30,0); setOnClickListener { player?.seekToNextMediaItem() } }
        
        miniPlayer.addView(miniIcon); miniPlayer.addView(miniTitle); miniPlayer.addView(btnMiniPrev); miniPlayer.addView(btnMiniPlayPause); miniPlayer.addView(btnMiniNext)
        
        libraryScreen.addView(libraryVBox); libraryScreen.addView(miniPlayer)


        // ================= 2. FULL PLAYER (GLASSY + VISUALIZER) =================
        fullPlayerBg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(currentBgColor, Color.parseColor("#050505")))
        
        fullPlayerScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = fullPlayerBg
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(40, 60, 40, 40)
        }

        val playerTopBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 40) }
        val btnCollapse = TextView(this).apply { text = "∨"; textSize = 28f; setTextColor(Color.WHITE); setPadding(0,0,40,0); setOnClickListener { fullPlayerScreen.visibility = View.GONE; libraryScreen.visibility = View.VISIBLE } }
        val volumeIcon = TextView(this).apply { text = "🔊"; textSize = 20f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        
        val btnOpenLab = createGlassButton("⚡ Sync Lab").apply { 
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 90)
            setTextColor(Color.parseColor("#FBBF24"))
            setOnClickListener { syncLabScreen.visibility = View.VISIBLE } 
        }
        
        playerTopBar.addView(btnCollapse); playerTopBar.addView(volumeIcon); playerTopBar.addView(btnOpenLab)

        mainAlbumArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 800).apply { setMargins(40, 20, 40, 40) }
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 45f; setColor(Color.parseColor("#1C1404")) }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 45f) } }
        }

        // Animated Graphical Visualizer embedded above title
        val visualizerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60).apply { setMargins(0, 0, 0, 20) }
        }
        val bars = Array(8) {
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(12, 10).apply { setMargins(8, 0, 8, 0) }
                background = GradientDrawable().apply { setColor(Color.parseColor("#FBBF24")); cornerRadius = 10f }
            }
        }
        bars.forEach { visualizerLayout.addView(it) }

        val visualizerHandler = Handler(Looper.getMainLooper())
        visualizerHandler.post(object : Runnable {
            override fun run() {
                if (player?.isPlaying == true) {
                    bars.forEach { it.layoutParams = LinearLayout.LayoutParams(12, (10..60).random()).apply { setMargins(8, 0, 8, 0) } }
                } else {
                    bars.forEach { it.layoutParams = LinearLayout.LayoutParams(12, 10).apply { setMargins(8, 0, 8, 0) } }
                }
                visualizerHandler.postDelayed(this, 120)
            }
        })

        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 0, 20, 40) }
        val textContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        mainTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 24f; typeface = Typeface.create("sans-serif", Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.MARQUEE; isSelected = true; isSingleLine = true }
        mainArtist = TextView(this).apply { setTextColor(Color.parseColor("#FDE68A")); textSize = 16f; maxLines = 1 }
        textContainer.addView(mainTitle); textContainer.addView(mainArtist)
        
        val heartIcon = TextView(this).apply { text = "♡"; textSize = 28f; setTextColor(Color.parseColor("#FBBF24")); setPadding(0,0,40,0) }
        infoRow.addView(textContainer); infoRow.addView(heartIcon)

        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) { if (p2 && !isClientDevice) player?.seekTo(p1.toLong()) }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        timeText = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; text = "0:00 / 0:00"; gravity = Gravity.CENTER; setPadding(0, 10, 0, 60) }

        val playControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 0, 20, 0) }
        val shuffleBtn = TextView(this).apply { text = "🔀"; textSize = 24f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.CENTER }
        val prevBtn = TextView(this).apply { text = "⏮"; textSize = 32f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.CENTER; setOnClickListener { player?.seekToPreviousMediaItem() } }
        btnPlayPauseFull = TextView(this).apply { text = "▶"; textSize = 48f; setTextColor(Color.parseColor("#FBBF24")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.CENTER; setOnClickListener { togglePlayPause() } }
        val nextBtn = TextView(this).apply { text = "⏭"; textSize = 32f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.CENTER; setOnClickListener { player?.seekToNextMediaItem() } }
        val repeatBtn = TextView(this).apply { text = "🔁"; textSize = 24f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); gravity = Gravity.CENTER }
        playControls.addView(shuffleBtn); playControls.addView(prevBtn); playControls.addView(btnPlayPauseFull); playControls.addView(nextBtn); playControls.addView(repeatBtn)

        fullPlayerScreen.addView(playerTopBar); fullPlayerScreen.addView(mainAlbumArt); fullPlayerScreen.addView(visualizerLayout); fullPlayerScreen.addView(infoRow); fullPlayerScreen.addView(seekBar); fullPlayerScreen.addView(timeText); fullPlayerScreen.addView(playControls)


        // ================= 3. SYNC LAB (SEPARATED UI + EMOJIS) =================
        syncLabScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; 
            background = rootBg // Follows the golden dark theme
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(40, 60, 40, 40)
        }

        val labTopBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 40) }
        val labTitle = TextView(this).apply { text = "⚡ Golden Sync Lab"; textSize = 22f; setTextColor(Color.parseColor("#FBBF24")); typeface = Typeface.DEFAULT_BOLD; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val btnCloseLab = TextView(this).apply { text = "✕"; textSize = 24f; setTextColor(Color.WHITE); setOnClickListener { syncLabScreen.visibility = View.GONE } }
        labTopBar.addView(labTitle); labTopBar.addView(btnCloseLab)

        // Glassy Emoji Buttons
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnHost = createGlassButton("✨ Host")
        val btnJoin = createGlassButton("🔮 Join")
        val btnLeave = createGlassButton("🚪 Leave").apply { visibility = View.GONE }
        row1.addView(btnHost); row1.addView(btnJoin); row1.addView(btnLeave)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnEQ = createGlassButton("🎛️ EQ")
        val btn3D = createGlassButton("📍 3D: CTR")
        row2.addView(btnEQ); row2.addView(btn3D)

        val eqContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply { cornerRadius = 35f; setColor(Color.argb(80, 0, 0, 0)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 0) }
        }

        btn3D.setOnClickListener {
            spatialPosition = when (spatialPosition) { "CENTER" -> "LEFT"; "LEFT" -> "RIGHT"; "RIGHT" -> "REAR"; else -> "CENTER" }
            btn3D.text = "📍 3D: ${spatialPosition.take(3)}"
            player?.volume = when (spatialPosition) { "LEFT", "RIGHT" -> 0.85f; "REAR" -> 0.5f; else -> 1.0f }
            Toast.makeText(this, "3D Audio Set: $spatialPosition", Toast.LENGTH_SHORT).show()
        }

        btnEQ.setOnClickListener {
            eqContainer.visibility = if (eqContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            val sessionId = player?.audioSessionId ?: 0
            if (sessionId != 0 && equalizer == null) setupEqualizer(sessionId)

            equalizer?.let { eq ->
                eqContainer.removeAllViews()
                val minEQ = eq.bandLevelRange[0]; val maxEQ = eq.bandLevelRange[1]
                val seekBars = mutableListOf<SeekBar>()

                val resetBtn = createGlassButton("Reset EQ").apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100).apply { setMargins(0, 0, 0, 30) }
                    setOnClickListener { for (i in 0 until eq.numberOfBands) { eq.setBandLevel(i.toShort(), 0); seekBars[i].progress = 0 - minEQ } }
                }
                eqContainer.addView(resetBtn)

                for (i in 0 until eq.numberOfBands) {
                    val band = i.toShort(); val freq = eq.getCenterFreq(band) / 1000
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 15, 0, 15) }
                    val label = TextView(this@MainActivity).apply { text = "${freq}Hz"; setTextColor(Color.WHITE); width = 130 }
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
            } ?: Toast.makeText(this, "Play a track first", Toast.LENGTH_SHORT).show()
        }

        val scrollLab = ScrollView(this).apply { addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(row1); addView(row2); addView(eqContainer) }) }
        syncLabScreen.addView(labTopBar); syncLabScreen.addView(scrollLab)

        // Assemble Root
        rootLayout.addView(libraryScreen); rootLayout.addView(fullPlayerScreen); rootLayout.addView(syncLabScreen)
        setContentView(rootLayout)

        // ================= 4. LOGIC & ADAPTERS =================
        trackAdapter = TrackAdapter(librarySongs) { song ->
            miniPlayer.visibility = View.VISIBLE
            updateUIMetadata(song)
            val mediaItem = MediaItem.fromUri(song.uri)
            player?.setMediaItem(mediaItem); player?.prepare(); player?.play()
        }
        libraryRecyclerView.adapter = trackAdapter

        if (checkPermissions()) loadLocalMusic()

        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.localConfiguration?.uri?.let { uri ->
                    val song = librarySongs.find { it.uri == uri }
                    song?.let {
                        updateUIMetadata(it)
                        animateBackgroundColor(extractAverageColor(it.uri))
                    }
                    if (isHostDevice) syncManager.broadcastAudioFile(uri)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val symbol = if (isPlaying) "⏸" else "▶"
                btnMiniPlayPause.text = symbol; btnPlayPauseFull.text = symbol
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                super.onAudioSessionIdChanged(audioSessionId); setupEqualizer(audioSessionId)
            }
        })

        val progressHandler = Handler(Looper.getMainLooper())
        progressHandler.post(object : Runnable {
            override fun run() {
                player?.let {
                    if (it.isPlaying) {
                        seekBar.max = it.duration.coerceAtLeast(0).toInt()
                        seekBar.progress = it.currentPosition.coerceAtLeast(0).toInt()
                        timeText.text = "${formatTime(it.currentPosition)} / ${formatTime(it.duration)}"
                    }
                }
                progressHandler.postDelayed(this, 1000)
            }
        })

        syncManager.onAudioReceived = { uri -> runOnUiThread { player?.setMediaItem(MediaItem.fromUri(uri)); player?.prepare(); player?.play(); miniTitle.text = "Syncing Stream..."; mainTitle.text = "Syncing Stream..."; miniPlayer.visibility = View.VISIBLE } }
        syncManager.onSyncTickReceived = { hostMs -> if (isClientDevice) { val drift = (player?.currentPosition ?: 0) - hostMs; if (kotlin.math.abs(drift) > 150) player?.seekTo(hostMs) } }

        btnHost.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = true; isClientDevice = false
                syncManager.startHosting("Host", { btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE; startHostSyncLoop() }, {})
            }
        }
        btnJoin.setOnClickListener {
            if (checkPermissions()) {
                isHostDevice = false; isClientDevice = true
                syncManager.startDiscovering({ btnHost.visibility = View.GONE; btnJoin.visibility = View.GONE; btnLeave.visibility = View.VISIBLE; btnPlayPauseFull.visibility = View.GONE; btnMiniPlayPause.visibility = View.GONE; seekBar.isEnabled = false }, {})
            }
        }
        btnLeave.setOnClickListener {
            syncManager.stopAllConnections(); player?.stop(); isHostDevice = false; isClientDevice = false; syncHandler.removeCallbacks(hostSyncRunnable)
            btnHost.visibility = View.VISIBLE; btnJoin.visibility = View.VISIBLE; btnLeave.visibility = View.GONE; btnPlayPauseFull.visibility = View.VISIBLE; btnMiniPlayPause.visibility = View.VISIBLE; seekBar.isEnabled = true
        }
    }

    private fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun updateUIMetadata(song: Song) {
        miniTitle.text = "${song.title} • ${song.artist}"
        mainTitle.text = song.title; mainArtist.text = song.artist
        ArtworkLoader.loadArtwork(this, song) { bitmap ->
            if (bitmap != null) mainAlbumArt.setImageBitmap(bitmap)
            else mainAlbumArt.setBackgroundColor(Color.parseColor("#1C1404"))
        }
    }

    private fun setupEqualizer(sessionId: Int) { try { equalizer?.release(); equalizer = Equalizer(0, sessionId).apply { enabled = true } } catch (_: Exception) {} }

    private fun extractAverageColor(uri: Uri): Int {
        return try {
            val mmr = MediaMetadataRetriever().apply { setDataSource(this@MainActivity, uri) }
            val artBytes = mmr.embeddedPicture; mmr.release()
            if (artBytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true); val color = scaled.getPixel(0, 0)
                scaled.recycle(); bitmap.recycle()
                val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
                hsv[1] = hsv[1].coerceAtLeast(0.4f); hsv[2] = hsv[2].coerceAtMost(0.25f) // Keep it dark and moody for glass effect
                Color.HSVToColor(hsv)
            } else Color.parseColor("#1C1404")
        } catch (_: Exception) { Color.parseColor("#1C1404") }
    }

    private fun animateBackgroundColor(targetColor: Int) {
        ValueAnimator.ofArgb(currentBgColor, targetColor).apply {
            duration = 1000
            addUpdateListener { animator -> fullPlayerBg.colors = intArrayOf(animator.animatedValue as Int, Color.parseColor("#050505")) }
            start()
        }
        currentBgColor = targetColor
    }

    private fun loadLocalMusic() {
        librarySongs.clear()
        val cursor = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0); var title = it.getString(1) ?: "Unknown Track"; var artist = it.getString(2)
                val albumId = it.getLong(3); val dur = it.getLong(4)
                if (artist.isNullOrBlank() || artist.equals("<unknown>", ignoreCase = true)) {
                    if (title.contains("-")) { val parts = title.split("-"); artist = parts[0].trim(); title = parts.subList(1, parts.size).joinToString("-").trim() } else if (title.contains("_")) { val parts = title.split("_"); artist = parts[0].trim() } else { artist = "Vidmate Download" }
                }
                librarySongs.add(Song(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), title, artist, albumId, dur))
            }
        }
        trackAdapter.notifyDataSetChanged()
    }

    private val hostSyncRunnable = object : Runnable { override fun run() { if (isHostDevice && player?.isPlaying == true) player?.currentPosition?.let { syncManager.sendSyncTick(it) }; syncHandler.postDelayed(this, 1500) } }
    private fun startHostSyncLoop() { syncHandler.removeCallbacks(hostSyncRunnable); syncHandler.post(hostSyncRunnable) }
    override fun onDestroy() { super.onDestroy(); syncHandler.removeCallbacks(hostSyncRunnable); equalizer?.release(); player?.release() }
    
    private fun checkPermissions(): Boolean {
        val req = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { req.addAll(listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.READ_MEDIA_AUDIO)) } else req.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) req.addAll(listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        val missing = req.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1); return false }
        return true
    }
}

