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
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
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

// --- CUSTOM ANIMATION EXTENSION ---
@SuppressLint("ClickableViewAccessibility")
fun View.setAnimatedClick(onClick: () -> Unit) {
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).setInterpolator(OvershootInterpolator()).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(OvershootInterpolator()).start()
                if (event.action == MotionEvent.ACTION_UP) {
                    v.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    onClick()
                }
            }
        }
        true
    }
}

// --- PROGRAMMATIC VECTOR GRAPHICS FACTORY (ZERO EMOJIS) ---
object GraphicIcons {
    private fun createGraphic(draw: (Canvas, Paint) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        draw(canvas, paint)
        return bmp
    }

    val play by lazy { createGraphic { c, p -> 
        val path = Path().apply { moveTo(30f, 20f); lineTo(80f, 50f); lineTo(30f, 80f); close() }
        p.style = Paint.Style.FILL; c.drawPath(path, p) 
    }}
    val pause by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.FILL; c.drawRoundRect(25f, 20f, 40f, 80f, 5f, 5f, p); c.drawRoundRect(60f, 20f, 75f, 80f, 5f, 5f, p)
    }}
    val next by lazy { createGraphic { c, p -> 
        val path = Path().apply { moveTo(20f, 25f); lineTo(60f, 50f); lineTo(20f, 75f); close() }
        p.style = Paint.Style.FILL; c.drawPath(path, p); c.drawRoundRect(65f, 25f, 75f, 75f, 3f, 3f, p)
    }}
    val prev by lazy { createGraphic { c, p -> 
        val path = Path().apply { moveTo(80f, 25f); lineTo(40f, 50f); lineTo(80f, 75f); close() }
        p.style = Paint.Style.FILL; c.drawPath(path, p); c.drawRoundRect(25f, 25f, 35f, 75f, 3f, 3f, p)
    }}
    val search by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.strokeCap = Paint.Cap.ROUND
        c.drawCircle(45f, 45f, 20f, p); c.drawLine(60f, 60f, 80f, 80f, p)
    }}
    val menu by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.FILL; c.drawCircle(50f, 25f, 6f, p); c.drawCircle(50f, 50f, 6f, p); c.drawCircle(50f, 75f, 6f, p)
    }}
    val sort by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(20f, 30f, 80f, 30f, p); c.drawLine(20f, 50f, 60f, 50f, p); c.drawLine(20f, 70f, 40f, 70f, p)
    }}
    val shuffle by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.strokeCap = Paint.Cap.ROUND
        val path1 = Path().apply { moveTo(20f, 70f); cubicTo(40f, 70f, 60f, 30f, 80f, 30f) }
        val path2 = Path().apply { moveTo(20f, 30f); cubicTo(40f, 30f, 60f, 70f, 80f, 70f) }
        c.drawPath(path1, p); c.drawPath(path2, p)
        p.style = Paint.Style.FILL; c.drawCircle(80f, 30f, 8f, p); c.drawCircle(80f, 70f, 8f, p)
    }}
    val repeat by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(20f, 20f, 80f, 80f, 45f, 270f, false, p)
        p.style = Paint.Style.FILL
        val path = Path().apply { moveTo(80f, 40f); lineTo(95f, 60f); lineTo(65f, 60f); close() }
        c.drawPath(path, p)
    }}
    val chevronDown by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val path = Path().apply { moveTo(20f, 40f); lineTo(50f, 70f); lineTo(80f, 40f) }
        c.drawPath(path, p)
    }}
    val volume by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.FILL
        c.drawRoundRect(20f, 40f, 40f, 60f, 4f, 4f, p)
        val path = Path().apply { moveTo(40f, 40f); lineTo(65f, 20f); lineTo(65f, 80f); lineTo(40f, 60f); close() }
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(65f, 35f, 75f, 65f, -60f, 120f, false, p)
        c.drawArc(65f, 20f, 90f, 80f, -60f, 120f, false, p)
    }}
    val heart by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val path = Path().apply { moveTo(50f, 80f); cubicTo(10f, 50f, 10f, 20f, 50f, 40f); cubicTo(90f, 20f, 90f, 50f, 50f, 80f) }
        c.drawPath(path, p)
    }}
    val add by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(50f, 20f, 50f, 80f, p); c.drawLine(20f, 50f, 80f, 50f, p)
    }}
    val musicNote by lazy { createGraphic { c, p -> 
        p.style = Paint.Style.FILL
        c.drawCircle(40f, 70f, 15f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 8f
        c.drawLine(51f, 70f, 51f, 25f, p)
        c.drawArc(51f, 25f, 75f, 55f, 180f, 90f, false, p)
    }}
}

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
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(40, 25, 40, 25)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 15) }
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 35f; setColor(Color.argb(25, 255, 255, 255)); setStroke(2, Color.argb(80, 251, 191, 36)) }
        }

        val albumArtView = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 40, 0) }
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 25f; setColor(Color.parseColor("#1C1404")) }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 25f) } }
        }

        val textLayout = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val title = TextView(parent.context).apply { setTextColor(Color.WHITE); textSize = 16f; maxLines = 1; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val artist = TextView(parent.context).apply { setTextColor(Color.parseColor("#FDE68A")); textSize = 13f; maxLines = 1 }
        
        val menuIcon = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(60, 60).apply { setMargins(20, 0, 0, 0) }
            setImageBitmap(GraphicIcons.menu); setColorFilter(Color.parseColor("#FBBF24"))
            setAnimatedClick { Toast.makeText(context, "Track Options", Toast.LENGTH_SHORT).show() }
        }

        textLayout.addView(title); textLayout.addView(artist)
        layout.addView(albumArtView); layout.addView(textLayout); layout.addView(menuIcon)
        return VH(layout, title, artist, albumArtView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = songs[position]
        holder.title.text = song.title; holder.artist.text = song.artist
        holder.albumArt.setImageDrawable(null)
        ArtworkLoader.loadArtwork(holder.layout.context, song) { bitmap ->
            if (bitmap != null) holder.albumArt.setImageBitmap(bitmap) else holder.albumArt.setBackgroundColor(Color.parseColor("#1C1404"))
        }
        holder.layout.setAnimatedClick { onClick(song) }
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
    private var isSortedByName = false
    
    private val librarySongs = mutableListOf<Song>()
    private lateinit var trackAdapter: TrackAdapter

    // UIs
    private lateinit var fullPlayerScreen: LinearLayout
    private lateinit var tabContentFrame: FrameLayout
    private lateinit var tracksView: LinearLayout
    private lateinit var syncLabView: LinearLayout

    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var btnMiniPlayPause: ImageView

    private lateinit var mainAlbumArt: ImageView
    private lateinit var mainTitle: TextView
    private lateinit var mainArtist: TextView
    private lateinit var btnPlayPauseFull: ImageView
    private lateinit var shuffleBtn: ImageView
    private lateinit var repeatBtn: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var fullPlayerBg: GradientDrawable
    private var currentBgColor = Color.parseColor("#1C1404") 

    // Glass Button Factory for Sync Lab
    private fun createGlassButton(title: String, onClickAction: (Button) -> Unit): Button {
        return Button(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 12f; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, 110, 1f).apply { setMargins(10, 10, 10, 20) }
            background = GradientDrawable().apply { 
                cornerRadius = 35f; setColor(Color.argb(40, 255, 255, 255)) 
                setStroke(2, Color.argb(150, 251, 191, 36)) 
            }
            setAnimatedClick { onClickAction(this) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        syncManager = AudioSyncManager(this)

        val rootBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1C1404"), Color.parseColor("#080602"))
        ).apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 1400f }
        val rootLayout = FrameLayout(this).apply { background = rootBg }

        // ================= 1. MAIN SCREEN (TABS & CONTENT) =================
        val mainScreen = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        val mainVBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); setPadding(20, 0, 20, 0)
        }

        // Top App Bar
        val topAppBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 50, 20, 20) }
        val appTitle = TextView(this).apply {
            text = "SyncBeat Pro"; textSize = 24f; setTextColor(Color.parseColor("#FBBF24"))
            typeface = Typeface.create("sans-serif", Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setShadowLayer(15f, 0f, 0f, Color.parseColor("#D97706"))
        }
        val searchIcon = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { setMargins(0, 0, 30, 0) }
            setImageBitmap(GraphicIcons.search); setColorFilter(Color.WHITE)
            setAnimatedClick { Toast.makeText(this@MainActivity, "Search Opened", Toast.LENGTH_SHORT).show() }
        }
        val menuIcon = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setImageBitmap(GraphicIcons.menu); setColorFilter(Color.WHITE)
            setAnimatedClick { Toast.makeText(this@MainActivity, "Settings Opened", Toast.LENGTH_SHORT).show() }
        }
        topAppBar.addView(appTitle); topAppBar.addView(searchIcon); topAppBar.addView(menuIcon)

        // Functional Tabs
        val tabsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 10, 0, 10) }
        val tabsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabNames = listOf("MUSIC SYNC", "Tracks", "Playlists", "Albums", "Artists", "Folders") // Custom tab added here
        val tabViews = mutableListOf<TextView>()
        
        tabNames.forEach { tabName ->
            val isDefault = tabName == "Tracks"
            val tabText = TextView(this).apply {
                text = tabName; textSize = 16f
                setTextColor(if (isDefault) Color.WHITE else Color.parseColor("#A08A4D"))
                typeface = if (isDefault) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
                setPadding(30, 10, 30, 20)
                setAnimatedClick {
                    // Visual state update
                    tabViews.forEach { tv -> tv.setTextColor(Color.parseColor("#A08A4D")); tv.typeface = Typeface.DEFAULT }
                    this.setTextColor(Color.WHITE); this.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    
                    // Functional Logic: Switch between Tracks and Music Sync Lab
                    if (tabName == "MUSIC SYNC") {
                        tracksView.visibility = View.GONE
                        syncLabView.visibility = View.VISIBLE
                    } else {
                        tracksView.visibility = View.VISIBLE
                        syncLabView.visibility = View.GONE
                    }
                }
            }
            tabViews.add(tabText); tabsLayout.addView(tabText)
        }
        tabsScroll.addView(tabsLayout)

        // Content Area (Holds both Tracks and Sync Lab)
        tabContentFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }

        // --- View A: Tracks View ---
        tracksView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        val trackHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 20, 20, 20) }
        val sortContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val sortGraphic = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(50, 50).apply { setMargins(0,0,15,0) }; setImageBitmap(GraphicIcons.sort); setColorFilter(Color.parseColor("#FDE68A")) }
        val sortText = TextView(this).apply { text = "Date added"; textSize = 14f; setTextColor(Color.parseColor("#FDE68A")) }
        sortContainer.addView(sortGraphic); sortContainer.addView(sortText)
        sortContainer.setAnimatedClick {
            isSortedByName = !isSortedByName; sortText.text = if (isSortedByName) "Name" else "Date added"
            loadLocalMusic()
        }
        val listShuffleIcon = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { setMargins(0, 0, 40, 0) }
            setImageBitmap(GraphicIcons.shuffle); setColorFilter(Color.WHITE)
            setAnimatedClick { toggleShuffleMode(this) }
        }
        val playAllIcon = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(70, 70); setImageBitmap(GraphicIcons.play); setColorFilter(Color.WHITE)
            setAnimatedClick { playEntireLibrary(0) }
        }
        trackHeader.addView(sortContainer); trackHeader.addView(listShuffleIcon); trackHeader.addView(playAllIcon)
        val libraryRecyclerView = RecyclerView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); layoutManager = LinearLayoutManager(this@MainActivity) }
        tracksView.addView(trackHeader); tracksView.addView(libraryRecyclerView)

        // --- View B: MUSIC SYNC Lab ---
        syncLabView = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(20, 40, 20, 0)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnHost = createGlassButton("Host Room") {
            if (checkPermissions()) {
                isHostDevice = true; isClientDevice = false
                syncManager.startHosting("Host", { startHostSyncLoop(); Toast.makeText(this, "Hosting started", Toast.LENGTH_SHORT).show() }, {})
            }
        }
        val btnJoin = createGlassButton("Join Room") {
            if (checkPermissions()) {
                isHostDevice = false; isClientDevice = true
                syncManager.startDiscovering({ Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show(); btnPlayPauseFull.visibility = View.GONE; btnMiniPlayPause.visibility = View.GONE; seekBar.isEnabled = false }, {})
            }
        }
        val btnLeave = createGlassButton("Leave Room") {
            syncManager.stopAllConnections(); player?.stop(); isHostDevice = false; isClientDevice = false; syncHandler.removeCallbacks(hostSyncRunnable)
            btnPlayPauseFull.visibility = View.VISIBLE; btnMiniPlayPause.visibility = View.VISIBLE; seekBar.isEnabled = true
            Toast.makeText(this, "Left Room", Toast.LENGTH_SHORT).show()
        }
        row1.addView(btnHost); row1.addView(btnJoin); row1.addView(btnLeave)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val eqContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply { cornerRadius = 35f; setColor(Color.argb(80, 0, 0, 0)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 0) }
        }

        val btnEQ = createGlassButton("Equalizer") {
            eqContainer.visibility = if (eqContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            val sessionId = player?.audioSessionId ?: 0
            if (sessionId != 0 && equalizer == null) setupEqualizer(sessionId)

            equalizer?.let { eq ->
                eqContainer.removeAllViews()
                val minEQ = eq.bandLevelRange[0]; val maxEQ = eq.bandLevelRange[1]
                val seekBars = mutableListOf<SeekBar>()

                val resetBtn = createGlassButton("Reset EQ") { 
                    for (i in 0 until eq.numberOfBands) { eq.setBandLevel(i.toShort(), 0); seekBars[i].progress = 0 - minEQ } 
                }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100).apply { setMargins(0, 0, 0, 30) } }
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
        val btn3D = createGlassButton("3D: CTR") { button ->
            spatialPosition = when (spatialPosition) { "CENTER" -> "LEFT"; "LEFT" -> "RIGHT"; "RIGHT" -> "REAR"; else -> "CENTER" }
            button.text = "3D: ${spatialPosition.take(3)}"
            player?.volume = when (spatialPosition) { "LEFT", "RIGHT" -> 0.85f; "REAR" -> 0.5f; else -> 1.0f }
            Toast.makeText(this, "3D Audio: $spatialPosition", Toast.LENGTH_SHORT).show()
        }
        row2.addView(btnEQ); row2.addView(btn3D)
        
        val syncLabScroll = ScrollView(this).apply { addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(row1); addView(row2); addView(eqContainer) }) }
        syncLabView.addView(syncLabScroll)

        tabContentFrame.addView(tracksView); tabContentFrame.addView(syncLabView)
        mainVBox.addView(topAppBar); mainVBox.addView(tabsScroll); mainVBox.addView(tabContentFrame)

        // Glassy Mini Player
        miniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(40, 20, 40, 20)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160).apply { gravity = Gravity.BOTTOM; setMargins(20, 0, 20, 40) }
            background = GradientDrawable().apply { setColor(Color.argb(80, 10, 10, 10)); cornerRadius = 75f; setStroke(3, Color.parseColor("#FBBF24")) }
            setAnimatedClick { mainScreen.visibility = View.GONE; fullPlayerScreen.visibility = View.VISIBLE }
        }

        val miniIcon = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(70, 70).apply { setMargins(0, 0, 30, 0) }; setImageBitmap(GraphicIcons.musicNote); setColorFilter(Color.WHITE) }
        miniTitle = TextView(this).apply {
            text = "No queued tracks"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); ellipsize = TextUtils.TruncateAt.MARQUEE; isSelected = true; isSingleLine = true
        }
        val btnMiniPrev = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(70, 70).apply { setMargins(0,0,30,0) }; setImageBitmap(GraphicIcons.prev); setColorFilter(Color.WHITE); setAnimatedClick { player?.seekToPreviousMediaItem() } }
        btnMiniPlayPause = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(70, 70).apply { setMargins(0,0,30,0) }; setImageBitmap(GraphicIcons.play); setColorFilter(Color.parseColor("#FBBF24")); setAnimatedClick { togglePlayPause() } }
        val btnMiniNext = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(70, 70); setImageBitmap(GraphicIcons.next); setColorFilter(Color.WHITE); setAnimatedClick { player?.seekToNextMediaItem() } }
        
        miniPlayer.addView(miniIcon); miniPlayer.addView(miniTitle); miniPlayer.addView(btnMiniPrev); miniPlayer.addView(btnMiniPlayPause); miniPlayer.addView(btnMiniNext)
        mainScreen.addView(mainVBox); mainScreen.addView(miniPlayer)


        // ================= 2. FULL PLAYER =================
        fullPlayerBg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(currentBgColor, Color.parseColor("#050505")))
        fullPlayerScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = fullPlayerBg
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); setPadding(40, 60, 40, 40)
        }

        val playerTopBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 40) }
        val btnCollapse = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { setMargins(0,0,40,0) }
            setImageBitmap(GraphicIcons.chevronDown); setColorFilter(Color.WHITE)
            setAnimatedClick { fullPlayerScreen.visibility = View.GONE; mainScreen.visibility = View.VISIBLE } 
        }
        val volumeIcon = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(80, 80); setImageBitmap(GraphicIcons.volume); setColorFilter(Color.WHITE) }
        val menuIconFull = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(80, 80); setImageBitmap(GraphicIcons.menu); setColorFilter(Color.WHITE) }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        playerTopBar.addView(btnCollapse); playerTopBar.addView(volumeIcon); playerTopBar.addView(spacer); playerTopBar.addView(menuIconFull)

        mainAlbumArt = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 800).apply { setMargins(40, 20, 40, 40) }
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 45f; setColor(Color.parseColor("#1C1404")) }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 45f) } }
        }

        val visualizerLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60).apply { setMargins(0, 0, 0, 20) } }
        val bars = Array(8) { View(this).apply { layoutParams = LinearLayout.LayoutParams(12, 10).apply { setMargins(8, 0, 8, 0) }; background = GradientDrawable().apply { setColor(Color.parseColor("#FBBF24")); cornerRadius = 10f } } }
        bars.forEach { visualizerLayout.addView(it) }

        val visualizerHandler = Handler(Looper.getMainLooper())
        visualizerHandler.post(object : Runnable {
            override fun run() {
                if (player?.isPlaying == true) { bars.forEach { it.layoutParams = LinearLayout.LayoutParams(12, (10..60).random()).apply { setMargins(8, 0, 8, 0) } } } 
                else { bars.forEach { it.layoutParams = LinearLayout.LayoutParams(12, 10).apply { setMargins(8, 0, 8, 0) } } }
                visualizerHandler.postDelayed(120)
            }
        })

        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 0, 20, 40) }
        val textContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        mainTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 24f; typeface = Typeface.create("sans-serif", Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.MARQUEE; isSelected = true; isSingleLine = true }
        mainArtist = TextView(this).apply { setTextColor(Color.parseColor("#FDE68A")); textSize = 16f; maxLines = 1 }
        textContainer.addView(mainTitle); textContainer.addView(mainArtist)
        
        val heartIcon = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { setMargins(0,0,40,0) }
            setImageBitmap(GraphicIcons.heart); setColorFilter(Color.parseColor("#FBBF24"))
            setAnimatedClick { Toast.makeText(this@MainActivity, "Added to Favourites", Toast.LENGTH_SHORT).show() }
        }
        val addIcon = ImageView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setImageBitmap(GraphicIcons.add); setColorFilter(Color.parseColor("#FBBF24"))
            setAnimatedClick { Toast.makeText(this@MainActivity, "Add to Playlist", Toast.LENGTH_SHORT).show() }
        }
        infoRow.addView(textContainer); infoRow.addView(heartIcon); infoRow.addView(addIcon)

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
        shuffleBtn = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(0, 80, 1f); setImageBitmap(GraphicIcons.shuffle); setColorFilter(Color.WHITE); setAnimatedClick { toggleShuffleMode(listShuffleIcon) } }
        val prevBtn = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(0, 100, 1f); setImageBitmap(GraphicIcons.prev); setColorFilter(Color.WHITE); setAnimatedClick { player?.seekToPreviousMediaItem() } }
        btnPlayPauseFull = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(0, 140, 1f); setImageBitmap(GraphicIcons.play); setColorFilter(Color.parseColor("#FBBF24")); setAnimatedClick { togglePlayPause() } }
        val nextBtn = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(0, 100, 1f); setImageBitmap(GraphicIcons.next); setColorFilter(Color.WHITE); setAnimatedClick { player?.seekToNextMediaItem() } }
        repeatBtn = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(0, 80, 1f); setImageBitmap(GraphicIcons.repeat); setColorFilter(Color.WHITE); setAnimatedClick { toggleRepeatMode() } }
        playControls.addView(shuffleBtn); playControls.addView(prevBtn); playControls.addView(btnPlayPauseFull); playControls.addView(nextBtn); playControls.addView(repeatBtn)
        fullPlayerScreen.addView(playerTopBar); fullPlayerScreen.addView(mainAlbumArt); fullPlayerScreen.addView(visualizerLayout); fullPlayerScreen.addView(infoRow); fullPlayerScreen.addView(seekBar); fullPlayerScreen.addView(timeText); fullPlayerScreen.addView(playControls)


        // Assemble Root
        rootLayout.addView(mainScreen); rootLayout.addView(fullPlayerScreen)
        setContentView(rootLayout)

        // ================= 3. LOGIC & ADAPTERS =================
        trackAdapter = TrackAdapter(librarySongs) { song ->
            val startIndex = librarySongs.indexOf(song)
            playEntireLibrary(startIndex)
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
                val activeBitmap = if (isPlaying) GraphicIcons.pause else GraphicIcons.play
                btnMiniPlayPause.setImageBitmap(activeBitmap)
                btnPlayPauseFull.setImageBitmap(activeBitmap)
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
    }

    private fun playEntireLibrary(startIndex: Int) {
        if (librarySongs.isEmpty()) return
        player?.clearMediaItems()
        librarySongs.forEach { player?.addMediaItem(MediaItem.fromUri(it.uri)) }
        player?.seekTo(startIndex, 0L); player?.prepare(); player?.play()
        miniPlayer.visibility = View.VISIBLE
    }

    private fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    private fun toggleShuffleMode(listHeaderShuffleBtn: ImageView) {
        val isShuffled = player?.shuffleModeEnabled ?: false
        player?.shuffleModeEnabled = !isShuffled
        val activeColor = Color.parseColor("#FBBF24"); val inactiveColor = Color.WHITE
        shuffleBtn.setColorFilter(if (!isShuffled) activeColor else inactiveColor)
        listHeaderShuffleBtn.setColorFilter(if (!isShuffled) activeColor else inactiveColor)
    }

    private fun toggleRepeatMode() {
        val currentMode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        val nextMode = if (currentMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player?.repeatMode = nextMode
        repeatBtn.setColorFilter(if (nextMode == Player.REPEAT_MODE_ALL) Color.parseColor("#FBBF24") else Color.WHITE)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun updateUIMetadata(song: Song) {
        miniTitle.text = "${song.title} • ${song.artist}"
        mainTitle.text = song.title; mainArtist.text = song.artist
        ArtworkLoader.loadArtwork(this, song) { bitmap ->
            if (bitmap != null) { mainAlbumArt.setImageBitmap(bitmap); }
            else { mainAlbumArt.setBackgroundColor(Color.parseColor("#1C1404")) }
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
                hsv[1] = hsv[1].coerceAtLeast(0.4f); hsv[2] = hsv[2].coerceAtMost(0.25f) 
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
        val sortOrder = if (isSortedByName) "${MediaStore.Audio.Media.TITLE} ASC" else "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        
        val cursor = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, sortOrder)
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
        if (::trackAdapter.isInitialized) trackAdapter.notifyDataSetChanged()
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

