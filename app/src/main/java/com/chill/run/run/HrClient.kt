package com.chill.run.run

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.chill.run.core.HeartRate
import java.util.UUID

/**
 * 心拍数を流している機器（時計など）に Bluetooth でつないで、数字を受け取り続ける。
 * つながりが切れたら、自動でつなぎ直す。
 */
@SuppressLint("MissingPermission")
class HrClient(
    context: Context,
    private val address: String,
    /** 心拍数が届くたびに呼ばれる（メインスレッド） */
    private val onHeartRate: (Int) -> Unit,
    /** つながりの状態が変わったときに呼ばれる（メインスレッド） */
    private val onStatus: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var closed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    post { onStatus("つながりました") }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    post { onStatus("切れました。つなぎ直しています…") }
                    // 少し待ってから、つなぎ直す
                    main.postDelayed({ if (!closed) reconnect() }, 4000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            val descriptor = ch?.getDescriptor(CCCD)
            if (ch == null || descriptor == null) {
                post { onStatus("この機器は心拍数を流していません") }
                return
            }
            g.setCharacteristicNotification(ch, true)
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            post { onStatus("心拍数を待っています（時計を走るモードにしてください）") }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != HR_MEASUREMENT) return
            // 肌から外れているときの数字は使わない
            if (HeartRate.contact(value) == false) {
                post { onStatus("時計が肌から浮いています") }
                return
            }
            val bpm = HeartRate.parse(value) ?: return
            if (bpm in 30..240) post { onStatus("受信中"); onHeartRate(bpm) }
        }
    }

    private fun post(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    val permitted: Boolean
        get() = appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** @return つなぎ始められたら true */
    fun start(): Boolean {
        if (!permitted) {
            onStatus("「付近のデバイス」の許可がありません")
            return false
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth がオフです")
            return false
        }
        return try {
            val device = adapter.getRemoteDevice(address)
            onStatus("つないでいます…")
            // autoConnect = true：機器が近くに来たら（走るモードにしたら）自動でつながる
            gatt = device.connectGatt(appContext, true, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        } catch (e: Exception) {
            onStatus("つなげませんでした")
            false
        }
    }

    private fun reconnect() {
        runCatching { gatt?.connect() }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
