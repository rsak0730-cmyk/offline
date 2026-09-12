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
    private var fftBytes: ByteArray? = null
    
    private val themeColor = Color.parseColor("#00FFD1") 
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        setShadowLayer(15f, 0f, 0f, themeColor) 
    }
    
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        style = Paint.Style.STROKE
        strokeWidth = 5f
        setShadowLayer(20f, 0f, 0f, themeColor) 
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor
        textSize = 120f
        textAlign = Paint.Align.CENTER
        setShadowLayer(15f, 0f, 0f, themeColor)
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.ITALIC) 
    }

    private var currentScale = 1.0f
    private val SMOOTHING_FACTOR = 0.2f

    fun linkToPlayer(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return
        
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        fftBytes = fft
                        
                        if (fft != null && fft.size > 10) {
                            var bassTotal = 0f
                            for (i in 2..8 step 2) {
                                val r = fft[i].toFloat()
                                val i2 = fft[i + 1].toFloat()
                                bassTotal += hypot(r, i2)
                            }
                            
                            val avgBass = bassTotal / 4f
                            val bassImpact = (avgBass / 150f).coerceIn(0f, 0.25f)
                            val targetScale = 1.0f - bassImpact 
                            
                            currentScale += (targetScale - currentScale) * SMOOTHING_FACTOR
                        }
                        
                        invalidate()
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
        
        canvas.save()
        canvas.scale(currentScale, currentScale, cx, cy)
        
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("M.B", cx, textY, textPaint)
        
        canvas.drawCircle(cx, cy, baseRadius, circlePaint)

        fftBytes?.let { fft ->
            val numBars = 72 
            val angleStep = (2 * Math.PI / numBars).toFloat()
            
            for (i in 0 until numBars) {
                val fftIndex = (i * 2 + 2).coerceAtMost(fft.size - 2)
                val r = fft[fftIndex].toFloat()
                val im = fft[fftIndex + 1].toFloat()
                
                val magnitude = hypot(r, im)
                val barHeight = (magnitude * 2.5f).coerceAtMost(baseRadius * 1.5f)
                val angle = i * angleStep - Math.PI.toFloat() / 2f 
                
                val startX = cx + cos(angle) * baseRadius
                val startY = cy + sin(angle) * baseRadius
                val stopX = cx + cos(angle) * (baseRadius + barHeight)
                val stopY = cy + sin(angle) * (baseRadius + barHeight)
                
                canvas.drawLine(startX, startY, stopX, stopY, paint)
            }
        }
        canvas.restore()
    }
}

