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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.RunType

object Palette {
    val bg = Color(0xFF0B1418)
    val card = Color(0xFF15232B)
    val line = Color(0xFF2A3A44)
    val text = Color(0xFFF2F6F8)
    val dim = Color(0xFF9DB0BA)
    val road = Color(0xFF2E9BD6)   // 海の青
    val trail = Color(0xFF3DAA6B)  // 森の緑
    val warn = Color(0xFFE8A33D)
    val danger = Color(0xFFD9534F)

    fun of(type: RunType) = if (type == RunType.TRAIL) trail else road
    fun of(type: String) = if (type == "TRAIL") trail else road
}

fun typeLabel(type: String) = if (type == "TRAIL") "トレイル" else "ロード"

@Composable
fun ChillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.road,
            secondary = Palette.trail,
            background = Palette.bg,
            surface = Palette.card,
            onPrimary = Color.White,
            onBackground = Palette.text,
            onSurface = Palette.text,
        ),
        content = content,
    )
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
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) color else Palette.card)
            .border(1.dp, if (selected) color else Palette.line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (!enabled) Palette.dim else if (selected) Color.White else Palette.text,
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
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) color else Palette.line)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SmallButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
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
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (onBack != null) {
            SmallButton("← 戻る", onClick = onBack)
            Spacer(Modifier.width(12.dp))
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
    }
}

/** − と ＋ で数を変える */
@Composable
fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallButton("−", Modifier.width(64.dp), onClick = onMinus)
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.width(120.dp), textAlign = TextAlign.Center)
        SmallButton("＋", Modifier.width(64.dp), onClick = onPlus)
    }
}

@Composable
fun RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(label, color = Palette.dim, fontSize = 13.sp)
        Text(value, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))
