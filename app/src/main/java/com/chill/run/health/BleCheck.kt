package com.chill.run.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.UUID

/**
 * 実機チェック用：まわりの Bluetooth 機器の中から、心拍数を標準の形式（Heart Rate、0x180D）で
 * 流しているものをさがし、実際につないで数字が届くかを確かめる。
 */
@SuppressLint("MissingPermission")
object BleCheck {
    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val NAME_HINT = Regex("amazfit|powerbuds|zepp|huami|btalk|zeblaze", RegexOption.IGNORE_CASE)

    private class Candidate(val device: BluetoothDevice, val name: String, val advertisesHr: Boolean, val how: String)

    /** 心拍数の通知の中身から、拍/分を取り出す */
    fun parseHeartRate(value: ByteArray): Int? {
        if (value.size < 2) return null
        val sixteenBit = value[0].toInt() and 0x01 != 0
        return if (sixteenBit) {
            if (value.size < 3) null else (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else value[1].toInt() and 0xFF
    }

    private fun short(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) "0x" + s.substring(4, 8).uppercase() else s
    }

    suspend fun run(context: Context, onProgress: (String) -> Unit): String {
        val sb = StringBuilder("【心拍数の機器（Bluetooth）】\n")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return sb.append("Bluetooth がオフです。オンにしてからもう一度試してください。").toString()
        val scanner = adapter.bluetoothLeScanner ?: return sb.append("Bluetooth のスキャンが使えません。").toString()

        // 1) まわりをさがす
        onProgress("まわりの機器をさがしています…（15秒）")
        val found = LinkedHashMap<String, ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val old = found[result.device.address]
                val hasHr = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
                if (old == null || hasHr) found[result.device.address] = result
            }
        }
        var scanError: String? = null
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            delay(15_000)
        } catch (e: Exception) {
            scanError = e.message
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        if (scanError != null) sb.appendLine("スキャンで問題が起きました：$scanError")

        val candidates = ArrayList<Candidate>()
        for (r in found.values) {
            val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: ""
            val hasHr = r.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
            if (hasHr || NAME_HINT.containsMatchIn(name)) {
                candidates += Candidate(r.device, name.ifEmpty { "（名前なし）" }, hasHr, "スキャンで発見、電波 ${r.rssi}dBm")
            }
        }
        // ペアリング済みの機器（音楽用の接続）も、名前が合えば試す
        runCatching {
            for (d in adapter.bondedDevices.orEmpty()) {
                val name = d.name ?: ""
                // 音楽専用（クラシック）の機器は除き、心拍数を流せる種類の機器は名前に関係なく試す
                val canBeLe = d.type == BluetoothDevice.DEVICE_TYPE_LE || d.type == BluetoothDevice.DEVICE_TYPE_DUAL
                if ((canBeLe || NAME_HINT.containsMatchIn(name)) && candidates.none { it.device.address == d.address }) {
                    candidates += Candidate(d, name.ifEmpty { "（名前なし）" }, false, "ペアリング済みの機器")
                }
            }
        }
        sb.appendLine("見つかった機器：全部で ${found.size} 台。うち心拍数（0x180D）を名乗っているもの ${candidates.count { it.advertisesHr }} 台")
        if (candidates.isEmpty()) {
            sb.appendLine("PowerBuds らしい機器も、心拍数を流している機器も見つかりませんでした。")
            return sb.toString().trimEnd()
        }

        // 2) 候補に1台ずつつないで、中身を見る
        var anyValues = false
        for (c in candidates.sortedByDescending { (if (it.advertisesHr) 2 else 0) + (if (NAME_HINT.containsMatchIn(it.name)) 1 else 0) }.take(6)) {
            onProgress("${c.name} につないで調べています…")
            sb.appendLine()
            sb.appendLine("■ ${c.name}（…${c.device.address.takeLast(5)}、${c.how}）")
            sb.appendLine("  心拍数を名乗っている：${if (c.advertisesHr) "はい" else "いいえ"}")
            val services = CompletableDeferred<List<UUID>?>()
            val values = Collections.synchronizedList(ArrayList<Int>())
            var subscribed = false
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                    else if (newState == BluetoothProfile.STATE_DISCONNECTED) services.complete(null)
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val ch = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                    if (ch != null) {
                        gatt.setCharacteristicNotification(ch, true)
                        ch.getDescriptor(CCCD)?.let {
                            gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            subscribed = true
                        }
                    }
                    services.complete(gatt.services.map { it.uuid })
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    if (characteristic.uuid == HR_MEASUREMENT) parseHeartRate(value)?.let { values.add(it) }
                }
            }
            var gatt: BluetoothGatt? = null
            try {
                gatt = c.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                val list = withTimeoutOrNull(12_000) { services.await() }
                if (list == null) {
                    sb.appendLine("  つながりませんでした（この番号は音楽用の接続で、心拍数用ではない可能性があります）")
                } else {
                    sb.appendLine("  つながりました。中の項目：${list.joinToString("、") { short(it) }.ifEmpty { "なし" }}")
                    if (HR_SERVICE in list) {
                        sb.appendLine("  Heart Rate（0x180D）：あり")
                        onProgress("${c.name} から心拍数が届くか待っています…（15秒）")
                        delay(15_000)
                        val got = values.toList()
                        if (got.isNotEmpty()) {
                            anyValues = true
                            sb.appendLine("  心拍数が届きました：${got.size} 回（${got.take(8).joinToString("、")} 拍/分）")
                        } else {
                            sb.appendLine("  15秒待ちましたが、数字は届きませんでした" + if (subscribed) "" else "（受け取りの申し込みができませんでした）")
                        }
                    } else {
                        sb.appendLine("  Heart Rate（0x180D）：なし")
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("  調べている途中で問題が起きました：${e.javaClass.simpleName} ${e.message ?: ""}")
            } finally {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
            }
        }
        sb.appendLine()
        sb.append(if (anyValues) "結果：心拍数を直接受け取れました。" else "結果：心拍数は受け取れませんでした。")
        return sb.toString()
    }
}
