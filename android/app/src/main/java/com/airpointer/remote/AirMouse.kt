package com.airpointer.remote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns phone rotation into cursor movement, like a laser pointer / TV air mouse.
 *
 * Uses the gyroscope (angular velocity) plus gravity so it works however the phone is held:
 * - turning left/right around the vertical axis (yaw) moves the cursor horizontally
 * - tilting the top edge up/down (pitch) moves it vertically
 */
class AirMouse(context: Context, private val onMove: (dx: Float, dy: Float) -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? =
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val lowPassGravity = gravitySensor?.type == Sensor.TYPE_ACCELEROMETER

    private val up = floatArrayOf(0f, 0f, 1f) // world "up" in phone coordinates
    private var lastTimestamp = 0L
    private var smoothX = 0f
    private var smoothY = 0f

    var speed = 1f
    var smoothing = 0.5f
    var running = false
        private set

    val available: Boolean get() = gyro != null

    fun start(): Boolean {
        if (gyro == null || running) return gyro != null
        lastTimestamp = 0L
        smoothX = 0f; smoothY = 0f
        sensors.registerListener(this, gyro, 5000) // ~200 Hz where supported
        gravitySensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        running = true
        return true
    }

    fun stop() {
        if (!running) return
        sensors.unregisterListener(this)
        running = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> updateGravity(event.values)
            Sensor.TYPE_GYROSCOPE -> onGyro(event)
        }
    }

    private fun updateGravity(v: FloatArray) {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (n < 1f) return
        val a = if (lowPassGravity) 0.1f else 1f
        for (i in 0..2) up[i] += a * (v[i] / n - up[i])
        val m = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
        for (i in 0..2) up[i] /= m
    }

    private fun onGyro(e: SensorEvent) {
        if (lastTimestamp == 0L) {
            lastTimestamp = e.timestamp
            return
        }
        val dt = (e.timestamp - lastTimestamp) / 1e9f
        lastTimestamp = e.timestamp
        if (dt <= 0f || dt > 0.1f) return

        val wx = e.values[0]
        val wy = e.values[1]
        val wz = e.values[2]

        // Yaw: rotation around world vertical.
        val yawRate = wx * up[0] + wy * up[1] + wz * up[2]

        // Pitch: rotation around the phone's X axis projected onto the horizontal plane.
        var rx = 1f - up[0] * up[0]
        var ry = -up[0] * up[1]
        var rz = -up[0] * up[2]
        val rn = sqrt(rx * rx + ry * ry + rz * rz)
        if (rn < 0.3f) {
            // Phone held sideways (X axis nearly vertical); fall back to raw X rotation.
            rx = 1f; ry = 0f; rz = 0f
        } else {
            rx /= rn; ry /= rn; rz /= rn
        }
        val pitchRate = wx * rx + wy * ry + wz * rz

        // Dead zone removes hand tremor and residual gyro drift without a hard "stick" feel.
        val rate = hypot(yawRate, pitchRate)
        val deadZone = 0.015f
        if (rate <= deadZone) {
            smoothX *= 0.5f; smoothY *= 0.5f
            return
        }
        val scale = (rate - deadZone) / rate

        // Pixels per radian grows with speed: precise when slow, quick across big/multiple screens when fast.
        val gain = speed * (900f + 1600f * min(rate / 2.5f, 1f))
        val dx = -yawRate * scale * dt * gain
        val dy = -pitchRate * scale * dt * gain

        val alpha = 1f - 0.85f * smoothing.coerceIn(0f, 1f)
        smoothX += alpha * (dx - smoothX)
        smoothY += alpha * (dy - smoothY)
        if (abs(smoothX) + abs(smoothY) > 0.0001f) onMove(smoothX, smoothY)
    }
}
