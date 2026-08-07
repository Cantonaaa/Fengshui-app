package com.fengshui.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI

/**
 * 磁北方位角助手：旋转矢量传感器 → 设备磁北航向（弧度 0..2π，顺时针，0=北）。
 * 仅用于引导用户把手机指向北向；北向由 AR 相机位姿确定（见 ScanScreen 校北）。
 */
class NorthHelper(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var enabled = false

    private var lastAzimuth: Double = 0.0
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    fun azimuth(): Double = lastAzimuth

    fun start() {
        if (enabled || rotSensor == null) return
        sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        enabled = true
    }

    fun stop() {
        if (!enabled) return
        sm.unregisterListener(this)
        enabled = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val a = orientation[0].toDouble()
        lastAzimuth = (a % (2 * PI) + 2 * PI) % (2 * PI)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
