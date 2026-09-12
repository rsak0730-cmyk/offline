package com.syncbeat.offline

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.view.View
import kotlin.math.hypot

class CircularVisualizerView(context: Context) : View(context) {

    private var visualizer: Visualizer? = null
    
    // Neon Cyan matching the screenshot
    private val themeColor = Color.parseColor("#00E5FF") 

    // Center "M.B" Text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 45f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Dark background for the center circle
    private val centerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#061118") 
        style = Paint.Style.FILL
    }

    // 1. Inner Solid Ring
    private val innerSolidRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
        setShadowLayer(15f, 0f, 0f, themeColor)
    }

    // 2. Middle Dashed Ring
    private val middleDashedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.STROKE
        strokeWidth = 5f
        // Creates the medium dashed line pattern
        pathEffect = DashPathEffect(floatArrayOf(35f, 20f), 0f) 
        setShadowLayer(15f, 0f, 0f, themeColor)
    }

    // 3. Outer Dotted Ring
    private val outerDashedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
        // Creates the small dot/dash pattern
        pathEffect = DashPathEffect(floatArrayOf(10f, 25f), 0f)
        setShadowLayer(15f, 0f, 0f, themeColor)
    }

    // Separation of Physics: Bass vs Vocals
    private var currentBassPulse = 1.0f
    private var targetBassPulse = 1.0f
    
    private var currentVocalVibrate = 1.0f
    private var targetVocalVibrate = 1.0f

    fun linkToPlayer(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return
        
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 128) return
                        
                        // 1. Analyze Bass (Sub, Low, Mid-Bass) -> Controls Rings
                        var bassTotal = 0f
                        for (i in 2..12 step 2) { 
                            bassTotal += hypot(fft[i].toFloat(), fft[i + 1].toFloat())
                        }
                        val avgBass = bassTotal / 6f
                        
                        // Bass pushes the rings inward (shrinks)
                        val bassImpact = (avgBass / 150f).coerceIn(0f, 0.25f)
                        targetBassPulse = 1.0f - bassImpact 
                        
                        // 2. Analyze Mids/Highs (Vocals/Melody) -> Controls Center Circle
                        var vocalTotal = 0f
                        for (i in 20..80 step 2) { 
                            vocalTotal += hypot(fft[i].toFloat(), fft[i + 1].toFloat())
                        }
                        val avgVocal = vocalTotal / 30f

                        // Vocals create a minimal, smooth outward vibration
                        val vocalImpact = (avgVocal / 100f).coerceIn(0f, 0.08f)
                        targetVocalVibrate = 1.0f + vocalImpact
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun release() {
        visualizer?.release()
        visualizer = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (width.coerceAtMost(height) / 3.2f)
        
        // Physics smoothing for fluid motion
        currentBassPulse += (targetBassPulse - currentBassPulse) * 0.35f
        currentVocalVibrate += (targetVocalVibrate - currentVocalVibrate) * 0.45f
        
        // --- LAYER 1: Center Circle (Reacts to Vocals) ---
        canvas.save()
        canvas.scale(currentVocalVibrate, currentVocalVibrate, cx, cy)
        
        val centerRadius = maxRadius * 0.35f
        canvas.drawCircle(cx, cy, centerRadius, centerBgPaint)
        
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("M.B", cx, textY, textPaint)
        
        canvas.restore()
        
        // --- LAYER 2: Outer Rings (Shrinks to Bass) ---
        canvas.save()
        canvas.scale(currentBassPulse, currentBassPulse, cx, cy)
        
        // Draw the 3 distinct rings from the screenshot
        canvas.drawCircle(cx, cy, maxRadius * 0.60f, innerSolidRing)
        canvas.drawCircle(cx, cy, maxRadius * 0.82f, middleDashedRing)
        canvas.drawCircle(cx, cy, maxRadius, outerDashedRing)
        
        canvas.restore()
        
        postInvalidateOnAnimation()
    }
}

