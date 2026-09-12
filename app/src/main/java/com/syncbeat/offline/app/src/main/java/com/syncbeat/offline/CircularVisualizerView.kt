package com.syncbeat.offline

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.view.View
import kotlin.math.hypot

class CircularVisualizerView(context: Context) : View(context) {

    private var visualizer: Visualizer? = null
    
    // Default Neon Cyan
    private var themeColor = Color.parseColor("#00E5FF") 

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 45f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val centerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#061118") 
        style = Paint.Style.FILL
    }

    private val innerSolidRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
        setShadowLayer(15f, 0f, 0f, themeColor)
    }

    private val middleDashedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(35f, 20f), 0f) 
        setShadowLayer(15f, 0f, 0f, themeColor)
    }

    private val outerDashedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(10f, 25f), 0f)
        setShadowLayer(15f, 0f, 0f, themeColor)
    }

    private var currentBassPulse = 1.0f
    private var targetBassPulse = 1.0f
    private var currentVocalVibrate = 1.0f
    private var targetVocalVibrate = 1.0f

    // NEW: Function to update the globe's color based on the thumbnail
    fun updateThemeColor(newColor: Int) {
        themeColor = newColor
        
        innerSolidRing.color = themeColor
        innerSolidRing.setShadowLayer(15f, 0f, 0f, themeColor)
        
        middleDashedRing.color = themeColor
        middleDashedRing.setShadowLayer(15f, 0f, 0f, themeColor)
        
        outerDashedRing.color = themeColor
        outerDashedRing.setShadowLayer(15f, 0f, 0f, themeColor)
        
        postInvalidate()
    }

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
                        
                        var bassTotal = 0f
                        for (i in 2..12 step 2) { 
                            bassTotal += hypot(fft[i].toFloat(), fft[i + 1].toFloat())
                        }
                        val avgBass = bassTotal / 6f
                        val bassImpact = (avgBass / 150f).coerceIn(0f, 0.25f)
                        targetBassPulse = 1.0f - bassImpact 
                        
                        var vocalTotal = 0f
                        for (i in 20..80 step 2) { 
                            vocalTotal += hypot(fft[i].toFloat(), fft[i + 1].toFloat())
                        }
                        val avgVocal = vocalTotal / 30f
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
        
        currentBassPulse += (targetBassPulse - currentBassPulse) * 0.35f
        currentVocalVibrate += (targetVocalVibrate - currentVocalVibrate) * 0.45f
        
        canvas.save()
        canvas.scale(currentVocalVibrate, currentVocalVibrate, cx, cy)
        val centerRadius = maxRadius * 0.35f
        canvas.drawCircle(cx, cy, centerRadius, centerBgPaint)
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("M.B", cx, textY, textPaint)
        canvas.restore()
        
        canvas.save()
        canvas.scale(currentBassPulse, currentBassPulse, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius * 0.60f, innerSolidRing)
        canvas.drawCircle(cx, cy, maxRadius * 0.82f, middleDashedRing)
        canvas.drawCircle(cx, cy, maxRadius, outerDashedRing)
        canvas.restore()
        
        postInvalidateOnAnimation()
    }
}

