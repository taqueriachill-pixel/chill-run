package com.chill.run

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.ui.ChillTheme
import com.chill.run.ui.Palette
import com.chill.run.ui.ScreenHeader

/** ヘルスコネクトの画面から開かれる「このアプリがデータをどう使うか」の説明 */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChillTheme {
                Column(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().padding(20.dp)) {
                    ScreenHeader("データの扱い", onBack = { finish() })
                    Text(
                        "Chill Run は自分専用のランニング記録アプリです。\n\n" +
                            "・ランの記録（距離、ルート、獲得標高、ケイデンス、消費カロリー）をヘルスコネクトに書き込みます。\n" +
                            "・心拍数、体重、体脂肪率などをヘルスコネクトから読み込み、このアプリの中での表示にだけ使います。\n" +
                            "・データはこのスマホの中にだけ保存します。外部のサーバーには送りません。\n" +
                            "・通信するのは、次のときだけです。\n" +
                            "　- 開始地点の標高を、国土地理院に問い合わせるとき（送るのは緯度と経度のみ）\n" +
                            "　- 地図を表示・一時保存するとき（国土地理院から地図を受け取る）\n" +
                            "　- ランムービーの曲づくりのために、走った日の天気と気温を Open-Meteo に問い合わせるとき（送るのは、約1km 単位に丸めた位置と日付のみ）",
                        color = Palette.text, fontSize = 16.sp, lineHeight = 26.sp,
                    )
                }
            }
        }
    }
}
