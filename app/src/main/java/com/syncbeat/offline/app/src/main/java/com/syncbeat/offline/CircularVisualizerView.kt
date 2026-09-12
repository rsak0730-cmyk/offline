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

    // Independent Physics Targets
    private var curSubBass = 1.0f; private var tarSubBass = 1.0f     // Outer Ring
    private var curMidBass = 1.0f; private var tarMidBass = 1.0f     // Middle Ring
    private var curHighBass = 1.0f; private var tarHighBass = 1.0f   // Inner Ring
    private var curVocal = 1.0f; private var tarVocal = 1.0f         // Center Circle

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
                captureSize = 1024 // High resolution for precise frequency slicing
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 256) return
                        
                        // 1. SUB-BASS (Outer Ring) ~ 20Hz to 60Hz
                        val subTotal = hypot(fft[2].toFloat(), fft[3].toFloat()) + hypot(fft[4].toFloat(), fft[5].toFloat())
                        tarSubBass = 1.0f - (subTotal / 300f).coerceIn(0f, 0.35f) 
                        
                        // 2. MID-BASS (Middle Ring) ~ 60Hz to 120Hz
                        val midTotal = hypot(fft[6].toFloat(), fft[7].toFloat()) + hypot(fft[8].toFloat(), fft[9].toFloat())
                        tarMidBass = 1.0f - (midTotal / 250f).coerceIn(0f, 0.25f)

                        // 3. HIGH-BASS (Inner Ring) ~ 120Hz to 250Hz
                        val highTotal = hypot(fft[10].toFloat(), fft[11].toFloat()) + hypot(fft[12].toFloat(), fft[13].toFloat()) + hypot(fft[14].toFloat(), fft[15].toFloat())
                        tarHighBass = 1.0f - (highTotal / 200f).coerceIn(0f, 0.15f)
                        
                        // 4. VOCALS / INSTRUMENTS (Center Circle) ~ 300Hz to 3000Hz
                        var vocalTotal = 0f
                        for (i in 16..140 step 2) { vocalTotal += hypot(fft[i].toFloat(), fft[i + 1].toFloat()) }
                        tarVocal = 1.0f + (vocalTotal / 2500f).coerceIn(0f, 0.15f)
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
        
        // Fluid, instant interpolation
        curSubBass += (tarSubBass - curSubBass) * 0.75f
        curMidBass += (tarMidBass - curMidBass) * 0.75f
        curHighBass += (tarHighBass - curHighBass) * 0.75f
        curVocal += (tarVocal - curVocal) * 0.80f
        
        // --- CENTER: Vocals ---
        canvas.save(); canvas.scale(curVocal, curVocal, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.35f, centerBgPaint)
        canvas.drawText("M.B", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        canvas.restore()
        
        // --- INNER RING: High-Bass ---
        canvas.save(); canvas.scale(curHighBass, curHighBass, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.60f, innerSolidRing)
        canvas.restore()

        // --- MIDDLE RING: Mid-Bass ---
        canvas.save(); canvas.scale(curMidBass, curMidBass, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.82f, middleDashedRing)
        canvas.restore()

        // --- OUTER RING: Sub-Bass ---
        canvas.save(); canvas.scale(curSubBass, curSubBass, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius, outerDashedRing)
        canvas.restore()
        
        postInvalidateOnAnimation()
    }
}

