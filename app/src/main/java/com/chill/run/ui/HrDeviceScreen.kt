package com.chill.run.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.data.Settings
import com.chill.run.run.HrClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Found(val address: String, val name: String, val note: String)

/** 心拍数を受け取る機器（時計など）を選んで、つながるかを試す画面 */
@SuppressLint("MissingPermission")
@Composable
fun HrDeviceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(settings.hrDeviceAddress) }
    var name by remember { mutableStateOf(settings.hrDeviceName) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf("") }
    var testBpm by remember { mutableStateOf<Int?>(null) }
    var client by remember { mutableStateOf<HrClient?>(null) }

    DisposableEffect(Unit) { onDispose { client?.close() } }

    fun stopTest() {
        client?.close(); client = null; testStatus = ""; testBpm = null
    }

    fun search() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            message = "Bluetooth がオフです。オンにしてからもう一度押してください。"
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { message = "Bluetooth のスキャンが使えません。"; return }
        scanning = true
        message = null
        scope.launch {
            val map = LinkedHashMap<String, Found>()
            // ペアリング済みの機器（音楽専用のものは除く）
            runCatching {
                for (d in adapter.bondedDevices.orEmpty()) {
                    if (d.type == BluetoothDevice.DEVICE_TYPE_LE || d.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                        map[d.address] = Found(d.address, d.name ?: "（名前なし）", "ペアリング済み")
                    }
                }
            }
            found = map.values.toList()
            // まわりで心拍数を流している機器
            val hrUuid = ParcelUuid(HrClient.HR_SERVICE)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result.scanRecord?.serviceUuids?.contains(hrUuid) == true) {
                        val n = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "（名前なし）"
                        map[result.device.address] = Found(result.device.address, n, "心拍数を流しています")
                        found = map.values.toList()
                    }
                }
            }
            try {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                delay(10_000)
            } catch (e: Exception) {
                message = "さがしている途中で問題が起きました：${e.message}"
            } finally {
                runCatching { scanner.stopScan(callback) }
                scanning = false
                if (map.isEmpty()) message = "見つかりませんでした。時計をスマホとペアリングしてあるか、時計が近くにあるかを確認してください。"
            }
        }
    }

    val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) search() else message = "「付近のデバイス」の許可が必要です。"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("心拍数の機器", onBack)

        Card {
            SectionTitle("いま使う機器")
            if (address.isEmpty()) {
                Text("まだ選んでいません。", color = Palette.text, fontSize = 16.sp)
            } else {
                Text(name.ifEmpty { "（名前なし）" }, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("番号 …${address.takeLast(8)}", color = Palette.dim, fontSize = 13.sp)
                if (client == null) {
                    SmallButton("つないで試す", Modifier.fillMaxWidth()) {
                        if (!permissions.all { Perms.has(context, it) }) {
                            message = "先に「さがす」を押して、許可を出してください。"
                        } else {
                            testBpm = null
                            client = HrClient(context, address, onHeartRate = { testBpm = it }, onStatus = { testStatus = it }).also { it.start() }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(testBpm?.toString() ?: "--", color = Palette.danger, fontSize = 56.sp, fontWeight = FontWeight.Black)
                        Text("拍/分", color = Palette.dim, fontSize = 16.sp)
                    }
                    Text(testStatus, color = Palette.warn, fontSize = 14.sp)
                    Text("時計を手首に付けて、時計の側で「走るモード」（ランニング）を始めると、数字が出ます。", color = Palette.dim, fontSize = 13.sp)
                    SmallButton("試すのをやめる", Modifier.fillMaxWidth()) { stopTest() }
                }
                SmallButton("この機器を使うのをやめる", Modifier.fillMaxWidth()) {
                    stopTest()
                    settings.hrDeviceAddress = ""; settings.hrDeviceName = ""
                    address = ""; name = ""
                }
            }
        }

        Card {
            SectionTitle("機器をさがす")
            Text("ペアリング済みの機器と、まわりで心拍数を流している機器を出します（10秒）。", color = Palette.dim, fontSize = 13.sp)
            SmallButton(if (scanning) "さがしています…" else "さがす", Modifier.fillMaxWidth(), enabled = !scanning) {
                if (permissions.all { Perms.has(context, it) }) search() else launcher.launch(permissions)
            }
            message?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
            found.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("${f.note}・…${f.address.takeLast(5)}", color = Palette.dim, fontSize = 12.sp)
                    }
                    SmallButton(if (f.address == address) "使用中" else "これを使う", enabled = f.address != address) {
                        stopTest()
                        settings.hrDeviceAddress = f.address; settings.hrDeviceName = f.name
                        address = f.address; name = f.name
                    }
                }
            }
        }
        Gap(24)
    }
}
