package com.syncbeat.offline

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class CircularVisualizerView(context: Context) : View(context) {

    private var visualizer: Visualizer? = null
    // 64 bars per half = 128 total bars for a dense, solid ring look
    private val numBars = 64 
    
    // Arrays for Avee Player style butter-smooth animations
    private val smoothedHeights = FloatArray(numBars)
    private val targetHeights = FloatArray(numBars)
    
    private val themeColor = Color.parseColor("#00FFFF") // High-intensity cyan
    
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        strokeWidth = 10f // Thick, dense bars
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        setShadowLayer(25f, 0f, 0f, themeColor) // Heavy neon bloom
    }
    
    private val circleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.STROKE
        strokeWidth = 12f // Thick solid border
        setShadowLayer(30f, 0f, 0f, themeColor)
    }
    
    private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0A0A")
        style = Paint.Style.FILL
        alpha = 220 // Dark inner circle to ground the text
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE 
        textSize = 150f
        textAlign = Paint.Align.CENTER
        setShadowLayer(35f, 0f, 0f, themeColor) // Glowing backdrop for white text
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.ITALIC) 
    }

    private var currentScale = 1.0f
    private var targetScale = 1.0f
    private val scaleSmoothing = 0.3f // Snappy bounce

    fun linkToPlayer(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return
        
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < numBars * 2) return
                        
                        // 1. Calculate heavy bass for the inward shrink effect
                        var bassTotal = 0f
                        for (i in 2..10 step 2) {
                            bassTotal += hypot(fft[i].toFloat(), fft[i + 1].toFloat())
                        }
                        val avgBass = bassTotal / 5f
                        val bassImpact = (avgBass / 150f).coerceIn(0f, 0.35f)
                        targetScale = 1.0f - bassImpact // Globe shrinks as bass hits

                        // 2. Map frequencies to target heights
                        for (i in 0 until numBars) {
                            val fftIndex = (i * 2 + 2).coerceAtMost(fft.size - 2)
                            val r = fft[fftIndex].toFloat()
                            val im = fft[fftIndex + 1].toFloat()
                            
                            val magnitude = hypot(r, im)
                            
                            // Exponential multiplier so high frequencies spike dynamically
                            val multiplier = 2.5f + (i * 0.08f)
                            targetHeights[i] = (magnitude * multiplier).coerceAtMost(400f)
                        }
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
        val baseRadius = (width.coerceAtMost(height) / 3.5f)
        
        // Apply smooth shrinking physics
        currentScale += (targetScale - currentScale) * scaleSmoothing
        
        canvas.save()
        canvas.scale(currentScale, currentScale, cx, cy)
        
        // Draw Globe Background & Outline
        canvas.drawCircle(cx, cy, baseRadius, circleFillPaint)
        canvas.drawCircle(cx, cy, baseRadius, circleOutlinePaint)
        
        // Draw Text
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("M.B", cx, textY, textPaint)

        // Draw Symmetrical Avee Player Spikes
        val angleStep = Math.PI.toFloat() / (numBars - 1)
        
        for (i in 0 until numBars) {
            // Lerp smoothing: Bars rise instantly to the beat, but fall slowly
            if (targetHeights[i] > smoothedHeights[i]) {
                smoothedHeights[i] += (targetHeights[i] - smoothedHeights[i]) * 0.7f 
            } else {
                smoothedHeights[i] -= (smoothedHeights[i] - targetHeights[i]) * 0.12f 
            }
            
            val h = smoothedHeights[i]
            if (h < 2f) continue 
            
            // Right Side (0 to 180 degrees)
            val angleRight = i * angleStep - Math.PI.toFloat() / 2f 
            val startXRight = cx + cos(angleRight) * baseRadius
            val startYRight = cy + sin(angleRight) * baseRadius
            val stopXRight = cx + cos(angleRight) * (baseRadius + h)
            val stopYRight = cy + sin(angleRight) * (baseRadius + h)
            canvas.drawLine(startXRight, startYRight, stopXRight, stopYRight, barPaint)

            // Left Side (Mirror)
            if (i > 0) { 
                val angleLeft = -Math.PI.toFloat() / 2f - i * angleStep
                val startXLeft = cx + cos(angleLeft) * baseRadius
                val startYLeft = cy + sin(angleLeft) * baseRadius
                val stopXLeft = cx + cos(angleLeft) * (baseRadius + h)
                val stopYLeft = cy + sin(angleLeft) * (baseRadius + h)
                canvas.drawLine(startXLeft, startYLeft, stopXLeft, stopYLeft, barPaint)
            }
        }
        
        canvas.restore()
        
        // Force constant 60fps redraw so the slow-fall physics keep animating smoothly
        postInvalidateOnAnimation()
    }
}

