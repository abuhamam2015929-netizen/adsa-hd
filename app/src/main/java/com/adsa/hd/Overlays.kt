package com.adsa.hd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2

/** شبكة الأثلاث (Rule of thirds) */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        strokeWidth = resources.displayMetrics.density * 1f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
        }
    }
}

/**
 * مؤشر أفق (Level) يعتمد على حساس التسارع.
 * الخط يصبح أصفر عندما يكون الهاتف مستوياً تماماً (مثل آيفون).
 */
class LevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = density * 2f
    }

    private var delta = 0f
    private var flat = false
    private var running = false

    fun start() {
        if (running || accelerometer == null) return
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        running = true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        flat = abs(z) > 8.3f
        var d = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat() % 90f
        if (d > 45f) d -= 90f
        if (d < -45f) d += 90f
        delta += 0.25f * (d - delta)
        invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDraw(canvas: Canvas) {
        if (flat) return
        val cx = width / 2f
        val cy = height / 2f
        val level = abs(delta) < 1f

        // علامات ثابتة على الجانبين
        paint.color = 0x99FFFFFF.toInt()
        val tick = density * 14f
        val gap = density * 78f
        canvas.drawLine(cx - gap - tick, cy, cx - gap, cy, paint)
        canvas.drawLine(cx + gap, cy, cx + gap + tick, cy, paint)

        // الخط المتحرك
        canvas.save()
        canvas.rotate(-delta, cx, cy)
        paint.color = if (level) Color.parseColor("#FFD60A") else 0xCCFFFFFF.toInt()
        val half = density * (if (level) 60f else 46f)
        canvas.drawLine(cx - half, cy, cx + half, cy, paint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
