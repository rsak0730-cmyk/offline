package com.syncbeat.offline

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.view.View
import kotlin.math.hypot

class CircularVisualizerView(context: Context) : View(context) {

    private var visualizer: Visualizer? = null
    private var themeColor = Color.parseColor("#00E5FF") 

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 45f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val centerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#061118"); style = Paint.Style.FILL }
    
    private val innerSolidRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor; style = Paint.Style.STROKE; strokeWidth = 6f; setShadowLayer(15f, 0f, 0f, themeColor) }
    private val middleDashedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor; style = Paint.Style.STROKE; strokeWidth = 5f; pathEffect = DashPathEffect(floatArrayOf(35f, 20f), 0f); setShadowLayer(15f, 0f, 0f, themeColor) }
    private val outerDashedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor; style = Paint.Style.STROKE; strokeWidth = 6f; pathEffect = DashPathEffect(floatArrayOf(10f, 25f), 0f); setShadowLayer(15f, 0f, 0f, themeColor) }

    private var currentBassPulse = 1.0f
    private var targetBassPulse = 1.0f
    private var currentVocalVibrate = 1.0f
    private var targetVocalVibrate = 1.0f

    fun updateThemeColor(newColor: Int) {
        themeColor = newColor
        innerSolidRing.color = themeColor; innerSolidRing.setShadowLayer(15f, 0f, 0f, themeColor)
        middleDashedRing.color = themeColor; middleDashedRing.setShadowLayer(15f, 0f, 0f, themeColor)
        outerDashedRing.color = themeColor; outerDashedRing.setShadowLayer(15f, 0f, 0f, themeColor)
        postInvalidate()
    }

    fun linkToPlayer(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return
        
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = 1024 // Ensures high resolution for accurate frequency separation
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 256) return
                        
                        // 1. FAST BASS (Indices 2 to 12 = ~40Hz to ~250Hz)
                        var bassTotal = 0f
                        for (i in 2..12 step 2) { bassTotal += hypot(fft[i].toFloat(), fft[i + 1].toFloat()) }
                        // Multiplied by 0.8f for extreme responsiveness
                        targetBassPulse = 1.0f - (bassTotal / 1200f).coerceIn(0f, 0.35f) 
                        
                        // 2. VOCALS & MELODY (Indices 14 to 100 = ~300Hz to ~2100Hz)
                        var vocalTotal = 0f
                        for (i in 14..100 step 2) { vocalTotal += hypot(fft[i].toFloat(), fft[i + 1].toFloat()) }
                        targetVocalVibrate = 1.0f + (vocalTotal / 2500f).coerceIn(0f, 0.15f)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    fun release() { visualizer?.release(); visualizer = null }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (width.coerceAtMost(height) / 3.2f)
        
        // Increased interpolation value (0.75f) for lightning-fast snap instead of slow floating
        currentBassPulse += (targetBassPulse - currentBassPulse) * 0.75f
        currentVocalVibrate += (targetVocalVibrate - currentVocalVibrate) * 0.80f
        
        // LAYER 1: Independent Vocal Core
        canvas.save()
        canvas.scale(currentVocalVibrate, currentVocalVibrate, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.35f, centerBgPaint)
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("M.B", cx, textY, textPaint)
        canvas.restore()
        
        // LAYER 2: Independent Bass Rings
        canvas.save()
        canvas.scale(currentBassPulse, currentBassPulse, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.60f, innerSolidRing)
        canvas.drawCircle(cx, cy, maxRadius * 0.82f, middleDashedRing)
        canvas.drawCircle(cx, cy, maxRadius, outerDashedRing)
        canvas.restore()
        
        postInvalidateOnAnimation()
    }
}

