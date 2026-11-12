package com.chill.run.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chill.run.health.Health

/** 許可の状態。足りない許可は、ボタン1つで出せる（またはその設定画面に飛べる） */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var hcGranted by remember { mutableStateOf<Set<String>>(emptySet()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) { hcGranted = Health.granted(context) }

    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        hcGranted = it
        refresh++
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }

    // refresh が変わるたびに調べ直す
    val location = remember(refresh) { Perms.location(context) }
    val notifications = remember(refresh) { Perms.notifications(context) }
    val activity = remember(refresh) { Perms.activity(context) }
    val battery = remember(refresh) { Perms.batteryExempt(context) }
    val microphone = remember(refresh) { Perms.microphone(context) }
    val camera = remember(refresh) { Perms.camera(context) }
    val hcAvailable = remember { Health.available(context) }
    val hcCount = hcGranted.intersect(Health.allPermissions).size

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("許可の状態", onBack)
        Text(
            "ボタンを押しても何も出ないときは、「アプリの設定を開く」から手で許可してください（Android は、2回断ると画面を出さなくなります）。",
            color = Palette.dim, fontSize = 13.sp,
        )

        PermCard(
            "位置情報（正確な位置）", location,
            "「アプリの使用中のみ」と「正確な位置情報を使用」を選んでください。",
            "許可する",
        ) { single.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

        PermCard("通知", notifications, "走っている間の表示に使います。", "許可する") {
            single.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        PermCard("身体活動（歩数センサー）", activity, "ケイデンスと自動の一時停止に使います。", "許可する") {
            single.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
        }

        PermCard(
            "マイク", microphone,
            "声のメモの録音と、文字にするのに使います。「アプリの使用時のみ」を選んでください。走り出す前に許可しておく必要があります。",
            "許可する",
        ) { single.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }

        PermCard("カメラ", camera, "走っている最中の写真と動画に使います。「アプリの使用時のみ」を選んでください。", "許可する") {
            single.launch(arrayOf(Manifest.permission.CAMERA))
        }

        PermCard(
            "電池の最適化の対象外", battery,
            "走っている最中に、スマホがアプリを止めてしまうのを防ぎます。",
            "対象外にする",
        ) {
            runCatching {
                context.startActivity(
                    Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }.onFailure { openAppSettings() }
        }

        Card {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (!hcAvailable) "×" else if (hcCount == Health.allPermissions.size) "✓" else "△",
                    color = if (hcCount == Health.allPermissions.size) Palette.trail else Palette.warn,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
                Text("ヘルスコネクト", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                if (!hcAvailable) "このスマホではヘルスコネクトが使えないようです。"
                else "許可 $hcCount / ${Health.allPermissions.size} 個。ランの書き込みと、心拍数・体重などの読み込みに使います。「すべて許可」を選んでください。",
                color = Palette.dim, fontSize = 14.sp,
            )
            if (hcAvailable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("許可する", Modifier.weight(1f)) { hcLauncher.launch(Health.allPermissions) }
                    SmallButton("ヘルスコネクトを開く", Modifier.weight(1f)) {
                        runCatching { context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context)) }
                    }
                }
            }
        }

        SmallButton("アプリの設定を開く", Modifier.fillMaxWidth()) { openAppSettings() }
        Gap(24)
    }
}

@Composable
private fun PermCard(title: String, ok: Boolean, note: String, action: String, onAction: () -> Unit) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ok) "✓" else "×", color = if (ok) Palette.trail else Palette.danger, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Text(note, color = Palette.dim, fontSize = 14.sp)
        if (!ok) SmallButton(action, onClick = onAction)
    }
}
