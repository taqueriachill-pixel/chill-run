package com.chill.run.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.data.AppDb
import com.chill.run.data.ShoeEntity
import com.chill.run.data.ShoeKm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** シューズの合計距離（登録する前の分も足す） */
fun shoeKm(shoe: ShoeEntity, km: List<ShoeKm>): Double = shoe.startKm + (km.firstOrNull { it.shoeId == shoe.id }?.km ?: 0.0)

/** シューズの登録と、走った距離 */
@Composable
fun ShoesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).runDao() }
    val scope = rememberCoroutineScope()
    val shoes by remember { dao.observeShoes() }.collectAsState(initial = emptyList())
    val kms by remember { dao.observeShoeKm() }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<ShoeEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<ShoeEntity?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("シューズ", onBack)
        Text("走る前にホームで選ぶと、シューズごとの距離がたまります。買い替えの目安を過ぎると知らせます。", color = Palette.dim, fontSize = 13.sp)
        BigButton("シューズを登録する", Modifier.fillMaxWidth(), color = Palette.earth) { editing = ShoeEntity(id = 0, name = "") }
        if (shoes.isEmpty()) Text("まだ登録がありません。", color = Palette.dim, fontSize = 15.sp)
        shoes.forEach { shoe ->
            val km = shoeKm(shoe, kms)
            val stat = kms.firstOrNull { it.shoeId == shoe.id }
            val over = km >= shoe.limitKm
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(shoe.name, color = if (shoe.retired) Palette.dim else Palette.text, fontSize = 19.sp, fontWeight = FontWeight.Black)
                        Text(
                            listOf(
                                when (shoe.kind) { "ROAD" -> "ロード用"; "TRAIL" -> "トレイル用"; else -> "どちらでも" },
                                "${stat?.runs ?: 0} 回",
                                if (shoe.retired) "引退" else "",
                            ).filter { it.isNotEmpty() }.joinToString("・"),
                            color = Palette.dim, fontSize = 13.sp,
                        )
                    }
                    Display("${"%.0f".format(km)} KM", 34, color = if (over && !shoe.retired) Palette.signal else Palette.earth, narrow = true)
                }
                // 買い替えの目安までの棒
                Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.line)) {
                    Box(Modifier.fillMaxWidth((km / shoe.limitKm).coerceIn(0.0, 1.0).toFloat()).height(6.dp).background(if (over) Palette.signal else Palette.earth))
                }
                Text(
                    if (over) "買い替えの目安（${"%.0f".format(shoe.limitKm)}km）を過ぎました。クッションがへたっていないか確かめてください。"
                    else "買い替えの目安まで あと ${"%.0f".format(shoe.limitKm - km)}km",
                    color = if (over) Palette.warn else Palette.dim, fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("直す", Modifier.weight(1f)) { editing = shoe }
                    SmallButton(if (shoe.retired) "現役に戻す" else "引退させる", Modifier.weight(1f)) {
                        scope.launch { withContext(Dispatchers.IO) { dao.updateShoe(shoe.copy(retired = !shoe.retired)) } }
                    }
                    SmallButton("消す", Modifier.weight(1f)) { confirmDelete = shoe }
                }
            }
        }
        Gap(24)
    }

    editing?.let { shoe ->
        var name by remember(shoe) { mutableStateOf(shoe.name) }
        var kind by remember(shoe) { mutableStateOf(shoe.kind) }
        var start by remember(shoe) { mutableStateOf(if (shoe.startKm > 0) "%.0f".format(shoe.startKm) else "") }
        var limit by remember(shoe) { mutableStateOf("%.0f".format(shoe.limitKm)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (shoe.id == 0L) "シューズを登録" else "シューズを直す") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("名前（例：ペガサス 41）") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("ロード", kind == "ROAD", Modifier.weight(1f), color = Palette.road) { kind = "ROAD" }
                        Chip("トレイル", kind == "TRAIL", Modifier.weight(1f), color = Palette.trail) { kind = "TRAIL" }
                        Chip("両方", kind == "", Modifier.weight(1f), color = Palette.earth) { kind = "" }
                    }
                    OutlinedTextField(start, { start = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("すでに走った距離（km）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("買い替えの目安（km）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("目安は、ふつう 500〜800km くらいと言われます。", color = Palette.dim, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val saved = shoe.copy(
                        name = name.trim(), kind = kind,
                        startKm = start.toDoubleOrNull() ?: 0.0,
                        limitKm = (limit.toDoubleOrNull() ?: 700.0).coerceAtLeast(50.0),
                        createdAt = if (shoe.id == 0L) System.currentTimeMillis() else shoe.createdAt,
                    )
                    editing = null
                    scope.launch { withContext(Dispatchers.IO) { if (saved.id == 0L) dao.insertShoe(saved) else dao.updateShoe(saved) } }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("やめる") } },
        )
    }

    confirmDelete?.let { shoe ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("「${shoe.name}」を消しますか？") },
            text = { Text("記録は消えません（そのシューズを選んでいた記録は「選んでいない」になります）。使い終わったシューズは「引退させる」のがおすすめです。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { withContext(Dispatchers.IO) { dao.clearShoe(shoe.id); dao.deleteShoe(shoe.id) } }
                }) { Text("消す", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("やめる") } },
        )
    }
}

/** ホームと記録の画面で使う、シューズを選ぶ並び */
@Composable
fun ShoePicker(shoes: List<ShoeEntity>, kms: List<ShoeKm>, selected: Long, type: String, onPick: (Long) -> Unit) {
    val usable = shoes.filter { !it.retired && (it.kind.isEmpty() || it.kind == type || it.id == selected) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        usable.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    Chip("${s.name}（${"%.0f".format(shoeKm(s, kms))}km）", s.id == selected, Modifier.weight(1f), color = Palette.earth) { onPick(s.id) }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
        Chip("シューズを選ばない", selected == 0L || usable.none { it.id == selected }, Modifier.fillMaxWidth(), color = Palette.earth) { onPick(0) }
    }
}
