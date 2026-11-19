package com.chill.run.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.Fmt
import com.chill.run.core.IntervalPlan
import com.chill.run.data.AppDb
import com.chill.run.data.IntervalMenuEntity
import com.chill.run.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun IntervalMenuEntity.toPlan() = IntervalPlan(warmupSec, runByDistance, runAmount, restSec, repeats, cooldownSec)

/** メニューを1行で説明する */
fun IntervalMenuEntity.summary(): String {
    val parts = ArrayList<String>()
    if (warmupSec > 0) parts += "ウォームアップ ${Fmt.durationSpoken(warmupSec.toLong())}"
    val run = if (runByDistance) "${Math.round(runAmount)}m" else Fmt.durationSpoken(runAmount.toLong())
    parts += if (restSec > 0 && repeats > 1) "（走る $run ＋ ゆっくり ${Fmt.durationSpoken(restSec.toLong())}）× $repeats 回"
    else "走る $run × $repeats 回"
    if (cooldownSec > 0) parts += "クールダウン ${Fmt.durationSpoken(cooldownSec.toLong())}"
    return parts.joinToString(" → ")
}

/** インターバルのメニューを作る・直す。menuId が 0 なら新しく作る */
@Composable
fun IntervalScreen(menuId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).runDao() }
    val scope = rememberCoroutineScope()
    var m by remember {
        mutableStateOf(
            IntervalMenuEntity(name = "", warmupSec = 300, runByDistance = false, runAmount = 60.0, restSec = 60, repeats = 5, cooldownSec = 300)
        )
    }
    var runSec by remember { mutableStateOf(60) }
    var runMeters by remember { mutableStateOf(400) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(menuId) {
        if (menuId > 0) withContext(Dispatchers.IO) { dao.menu(menuId) }?.let {
            m = it
            if (it.runByDistance) runMeters = it.runAmount.toInt() else runSec = it.runAmount.toInt()
        }
    }
    val current = m.copy(runAmount = if (m.runByDistance) runMeters.toDouble() else runSec.toDouble())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(if (menuId > 0) "メニューを直す" else "メニューを作る", onBack)

        Card {
            SectionTitle("名前")
            OutlinedTextField(
                value = m.name, onValueChange = { m = m.copy(name = it.take(20)) },
                singleLine = true, label = { Text("例：1分×5本") }, modifier = Modifier.fillMaxWidth(),
            )
        }

        Card {
            SectionTitle("ウォームアップ（0 なら付けない）")
            Stepper(
                if (m.warmupSec == 0) "なし" else "${m.warmupSec / 60} 分",
                onMinus = { m = m.copy(warmupSec = (m.warmupSec - 60).coerceAtLeast(0)) },
                onPlus = { m = m.copy(warmupSec = (m.warmupSec + 60).coerceAtMost(1800)) },
            )
        }

        Card {
            SectionTitle("走る")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("時間で決める", !m.runByDistance, Modifier.weight(1f)) { m = m.copy(runByDistance = false) }
                Chip("距離で決める", m.runByDistance, Modifier.weight(1f)) { m = m.copy(runByDistance = true) }
            }
            if (m.runByDistance) {
                Stepper(
                    "$runMeters m",
                    onMinus = { runMeters = (runMeters - 100).coerceAtLeast(100) },
                    onPlus = { runMeters = (runMeters + 100).coerceAtMost(5000) },
                )
            } else {
                Stepper(
                    Fmt.clock(runSec * 1000L),
                    onMinus = { runSec = (runSec - 15).coerceAtLeast(15) },
                    onPlus = { runSec = (runSec + 15).coerceAtMost(1800) },
                )
            }
            SectionTitle("歩く・ゆっくり")
            Stepper(
                if (m.restSec == 0) "なし" else Fmt.clock(m.restSec * 1000L),
                onMinus = { m = m.copy(restSec = (m.restSec - 15).coerceAtLeast(0)) },
                onPlus = { m = m.copy(restSec = (m.restSec + 15).coerceAtMost(900)) },
            )
            SectionTitle("くり返す回数")
            Stepper(
                "${m.repeats} 回",
                onMinus = { m = m.copy(repeats = (m.repeats - 1).coerceAtLeast(1)) },
                onPlus = { m = m.copy(repeats = (m.repeats + 1).coerceAtMost(40)) },
            )
        }

        Card {
            SectionTitle("クールダウン（0 なら付けない）")
            Stepper(
                if (m.cooldownSec == 0) "なし" else "${m.cooldownSec / 60} 分",
                onMinus = { m = m.copy(cooldownSec = (m.cooldownSec - 60).coerceAtLeast(0)) },
                onPlus = { m = m.copy(cooldownSec = (m.cooldownSec + 60).coerceAtMost(1800)) },
            )
        }

        Card {
            SectionTitle("このメニュー")
            Text(current.summary(), color = Palette.text, fontSize = 15.sp)
            Text(
                "切り替えの3秒前から「3、2、1」と声が出て、切り替わると振動します。インターバル中は、止まっても時間は止まりません。",
                color = Palette.dim, fontSize = 13.sp,
            )
        }

        error?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
        BigButton("保存する", Modifier.fillMaxWidth()) {
            val toSave = current.copy(name = current.name.trim().ifEmpty { defaultName(current) })
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        if (toSave.id > 0) { dao.updateMenu(toSave); toSave.id } else dao.insertMenu(toSave)
                    }
                }
                result.onSuccess { Settings(context).lastMenuId = it; onBack() }
                    .onFailure { error = "保存できませんでした：${it.message}" }
            }
        }
        if (menuId > 0) {
            SmallButton("このメニューを消す", Modifier.fillMaxWidth()) {
                scope.launch {
                    withContext(Dispatchers.IO) { dao.deleteMenu(menuId) }
                    onBack()
                }
            }
        }
        Gap(24)
    }
}

private fun defaultName(m: IntervalMenuEntity): String {
    val run = if (m.runByDistance) "${Math.round(m.runAmount)}m" else Fmt.durationSpoken(m.runAmount.toLong())
    return "$run×${m.repeats}本"
}
