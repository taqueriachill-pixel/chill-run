package com.chill.run.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager

/** 許可の状態を調べる小さな道具 */
object Perms {
    fun has(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun location(context: Context) = has(context, Manifest.permission.ACCESS_FINE_LOCATION)
    fun notifications(context: Context) = has(context, Manifest.permission.POST_NOTIFICATIONS)
    fun activity(context: Context) = has(context, Manifest.permission.ACTIVITY_RECOGNITION)
    fun microphone(context: Context) = has(context, Manifest.permission.RECORD_AUDIO)
    fun camera(context: Context) = has(context, Manifest.permission.CAMERA)
    fun batteryExempt(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    val basic = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACTIVITY_RECOGNITION,
    )
}
