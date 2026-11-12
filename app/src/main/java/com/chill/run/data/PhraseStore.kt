package com.chill.run.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/** 励ましの言葉。自分で書き換えたファイルがあればそれを、なければアプリに入っているものを使う */
object PhraseStore {
    private fun customFile(context: Context) = File(context.filesDir, "phrases.json")

    private fun defaultText(context: Context): String =
        context.assets.open("phrases.json").bufferedReader().use { it.readText() }

    fun currentText(context: Context): String {
        val f = customFile(context)
        return if (f.exists()) f.readText() else defaultText(context)
    }

    fun isCustom(context: Context) = customFile(context).exists()

    fun load(context: Context): Map<String, List<String>> =
        runCatching { parse(currentText(context)) }.getOrElse { parse(defaultText(context)) }

    fun parse(text: String): Map<String, List<String>> {
        val obj = JSONObject(text)
        val map = LinkedHashMap<String, List<String>>()
        for (key in obj.keys()) {
            val arr = obj.getJSONArray(key)
            map[key] = (0 until arr.length()).map { arr.getString(it) }
        }
        return map
    }

    fun export(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(currentText(context).toByteArray()) }
            ?: error("書き出し先を開けませんでした")
    }

    /** 読み込む。形がおかしければ例外を投げて、いまの言葉は変えない */
    fun import(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("ファイルを開けませんでした")
        val parsed = parse(text)
        require(parsed.isNotEmpty()) { "言葉が1つも入っていません" }
        customFile(context).writeText(text)
        return parsed.values.sumOf { it.size }
    }

    fun reset(context: Context) {
        customFile(context).delete()
    }
}
