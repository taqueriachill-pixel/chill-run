package com.chill.run.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.chill.run.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.RunType

/**
 * 色（2026-11-15 に本人と決めた「H：アウトドア誌」）。
 * 生成りの紙、深い緑（ロード）、信号のオレンジ（トレイル）、砂色の線。
 */
object Palette {
    val bg = Color(0xFFF3EFE6)      // 生成りの紙
    val card = Color(0xFFFBF9F4)    // 少し明るい紙
    val line = Color(0xFFD8CFBD)    // 砂色の線
    val text = Color(0xFF1F2A21)    // 墨（緑がかった黒）
    val dim = Color(0xFF6C6556)     // 小さな説明の文字
    val earth = Color(0xFF2D3A2E)   // 深い緑
    val signal = Color(0xFFE8571E)  // 信号のオレンジ
    val road = earth
    val trail = signal
    /** ランニングマシン（スレートの青） */
    val machine = Color(0xFF3E5C76)
    val warn = Color(0xFFA34A0C)
    val danger = Color(0xFFB3261E)
    val ok = Color(0xFF3F7A4A)

    /** 心拍ゾーンの色（0 はゾーン外） */
    val zones = listOf(Color(0xFF9C9484), Color(0xFF6F8FA8), Color(0xFF4F8A5B), Color(0xFFC9920F), Color(0xFFE8571E), Color(0xFFB3261E))
    fun zone(z: Int) = zones[z.coerceIn(0, 5)]

    fun of(type: RunType) = when (type) { RunType.TRAIL -> trail; RunType.TREADMILL -> machine; else -> road }
    fun of(type: String) = when (type) { "TRAIL" -> trail; "TREADMILL" -> machine; else -> road }

    /** 色を塗った上に乗せる文字の色（オレンジの上は濃い色、緑や赤の上は白） */
    fun on(color: Color): Color = if (color == signal || color == zones[3] || color == card || color == bg) Color(0xFF1E140E) else Color.White
}

/** 数字と英字の書体（Archivo。字の幅を細くして使う。SIL Open Font License） */
@OptIn(ExperimentalTextApi::class)
object Type {
    private fun f(width: Float, weight: Int) = Font(
        R.font.archivo, FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
    )
    /** いちばん細長い太字（大きな数字・見出し） */
    val condensed = FontFamily(f(62f, 900), f(62f, 800))
    /** 少し細長い字（小さめの数字・ラベル） */
    val narrow = FontFamily(f(75f, 800), f(75f, 700), f(75f, 500))
}

fun typeLabel(type: String) = when (type) { "TRAIL" -> "トレイル"; "TREADMILL" -> "マシン"; else -> "ロード" }

/** 種類の英語の見出し */
fun typeWord(type: String) = when (type) { "TRAIL" -> "TRAIL"; "TREADMILL" -> "TREADMILL"; else -> "ROAD" }

@Composable
fun ChillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.earth,
            secondary = Palette.signal,
            background = Palette.bg,
            surface = Palette.card,
            onPrimary = Color.White,
            onBackground = Palette.text,
            onSurface = Palette.text,
        ),
        content = content,
    )
}

/** 大きな数字・英字の見出し（細長い太字） */
@Composable
fun Display(text: String, size: Int, modifier: Modifier = Modifier, color: Color = Palette.text, narrow: Boolean = false) {
    Text(
        text, modifier = modifier, color = color,
        fontFamily = if (narrow) Type.narrow else Type.condensed,
        fontWeight = if (narrow) FontWeight(800) else FontWeight(900),
        fontSize = size.sp, lineHeight = (size * 0.92f).sp,
    )
}

/** 小さな見出しの上に引く、太い線（雑誌の誌面のように） */
@Composable
fun Rule(modifier: Modifier = Modifier, thick: Boolean = true) {
    Box(modifier.fillMaxWidth().height(if (thick) 3.dp else 1.dp).background(if (thick) Palette.earth else Palette.line))
}

/** 角の丸い、押せる箱。選ばれているときは色がつく */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Palette.road,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) color else Color.Transparent)
            .border(1.5.dp, if (selected) color else Palette.line, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (!enabled) Palette.dim else if (selected) Palette.on(color) else Palette.text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun BigButton(label: String, modifier: Modifier = Modifier, color: Color = Palette.road, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled) color else Palette.line)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Palette.on(color) else Palette.dim, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SmallButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.5.dp, if (enabled) Palette.earth else Palette.line, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Palette.text else Palette.dim, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Palette.card)
            .border(1.dp, Palette.line, RoundedCornerShape(4.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (onBack != null) {
            SmallButton("← 戻る", onClick = onBack)
            Spacer(Modifier.width(12.dp))
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.text)
    }
}

/** − と ＋ で数を変える */
@Composable
fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallButton("−", Modifier.width(64.dp), onClick = onMinus)
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.width(150.dp), textAlign = TextAlign.Center)
        SmallButton("＋", Modifier.width(64.dp), onClick = onPlus)
    }
}

@Composable
fun RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(label, color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Palette.text, fontSize = 28.sp, fontFamily = Type.narrow, fontWeight = FontWeight(800))
    }
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))
