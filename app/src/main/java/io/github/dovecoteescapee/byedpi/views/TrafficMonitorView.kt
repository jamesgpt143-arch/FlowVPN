package io.github.dovecoteescapee.byedpi.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.R
import java.util.LinkedList

class TrafficMonitorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxDataPoints = 60
    private val dataPoints = LinkedList<Float>()
    
    private val linePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.teal_accent)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.teal_glow)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val path = Path()
    private val fillPath = Path()

    init {
        // Initialize with zeros
        for (i in 0 until maxDataPoints) {
            dataPoints.add(0f)
        }
    }

    fun addDataPoint(speedKbps: Float) {
        if (dataPoints.size >= maxDataPoints) {
            dataPoints.removeFirst()
        }
        dataPoints.add(speedKbps)
        invalidate()
    }

    fun clear() {
        dataPoints.clear()
        for (i in 0 until maxDataPoints) {
            dataPoints.add(0f)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (dataPoints.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        
        // Find max to scale
        var maxVal = dataPoints.maxOrNull() ?: 0f
        if (maxVal < 100f) maxVal = 100f // Minimum scale

        val dx = width / (maxDataPoints - 1)
        val scaleY = height / maxVal

        path.reset()
        fillPath.reset()

        var prevX = 0f
        var prevY = height - (dataPoints[0] * scaleY)
        
        path.moveTo(prevX, prevY)
        fillPath.moveTo(prevX, height)
        fillPath.lineTo(prevX, prevY)

        for (i in 1 until dataPoints.size) {
            val currX = prevX + dx
            val currY = height - (dataPoints[i] * scaleY)
            
            val cx1 = prevX + dx / 2f
            val cy1 = prevY
            val cx2 = currX - dx / 2f
            val cy2 = currY
            
            path.cubicTo(cx1, cy1, cx2, cy2, currX, currY)
            fillPath.cubicTo(cx1, cy1, cx2, cy2, currX, currY)
            
            prevX = currX
            prevY = currY
        }

        fillPath.lineTo(width, height)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
