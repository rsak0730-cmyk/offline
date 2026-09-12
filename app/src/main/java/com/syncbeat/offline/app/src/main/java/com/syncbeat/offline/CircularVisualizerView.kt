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

    // Independent Physics Targets for True Separation
    private var curSubBass = 1.0f; private var tarSubBass = 1.0f     
    private var curMidBass = 1.0f; private var tarMidBass = 1.0f     
    private var curHighBass = 1.0f; private var tarHighBass = 1.0f   
    private var curVocal = 1.0f; private var tarVocal = 1.0f         

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
                // FIXED: Safely requests maximum device limit instead of crashing on hardcoded 1024
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 64) return
                        val n = fft.size
                        
                        // 1. SUB-BASS (Outer Ring)
                        var subTotal = 0f
                        for (i in 2..6 step 2) { if (i+1 < n) subTotal += hypot(fft[i].toFloat(), fft[i+1].toFloat()) }
                        tarSubBass = 1.0f - (subTotal / 300f).coerceIn(0f, 0.35f) 
                        
                        // 2. MID-BASS (Middle Ring)
                        var midTotal = 0f
                        for (i in 8..14 step 2) { if (i+1 < n) midTotal += hypot(fft[i].toFloat(), fft[i+1].toFloat()) }
                        tarMidBass = 1.0f - (midTotal / 250f).coerceIn(0f, 0.25f)

                        // 3. HIGH-BASS (Inner Ring)
                        var highTotal = 0f
                        for (i in 16..24 step 2) { if (i+1 < n) highTotal += hypot(fft[i].toFloat(), fft[i+1].toFloat()) }
                        tarHighBass = 1.0f - (highTotal / 200f).coerceIn(0f, 0.15f)
                        
                        // 4. VOCALS / INSTRUMENTS (Center Circle)
                        var vocalTotal = 0f
                        var count = 0
                        for (i in 26..140 step 2) { 
                            if (i+1 < n) {
                                vocalTotal += hypot(fft[i].toFloat(), fft[i+1].toFloat())
                                count++
                            }
                        }
                        tarVocal = 1.0f + (vocalTotal / (count * 40f).coerceAtLeast(1f)).coerceIn(0f, 0.15f)
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    fun release() { 
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null 
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (width.coerceAtMost(height) / 3.2f)
        
        // Fluid interpolation for instant, snappy reactions
        curSubBass += (tarSubBass - curSubBass) * 0.45f
        curMidBass += (tarMidBass - curMidBass) * 0.45f
        curHighBass += (tarHighBass - curHighBass) * 0.45f
        curVocal += (tarVocal - curVocal) * 0.50f
        
        // LAYER 1: Center Vocals (Vibrates Outward)
        canvas.save(); canvas.scale(curVocal, curVocal, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.35f, centerBgPaint)
        canvas.drawText("M.B", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        canvas.restore()
        
        // LAYER 2: Inner Ring High-Bass (Shrinks Inward)
        canvas.save(); canvas.scale(curHighBass, curHighBass, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.60f, innerSolidRing)
        canvas.restore()

        // LAYER 3: Middle Ring Mid-Bass (Shrinks Inward)
        canvas.save(); canvas.scale(curMidBass, curMidBass, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.82f, middleDashedRing)
        canvas.restore()

        // LAYER 4: Outer Ring Sub-Bass (Shrinks Inward)
        canvas.save(); canvas.scale(curSubBass, curSubBass, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius, outerDashedRing)
        canvas.restore()
        
        postInvalidateOnAnimation()
    }
}

