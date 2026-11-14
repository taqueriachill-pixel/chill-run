package com.chill.run.core

/**
 * プレイリスト（m3u・m3u8）を読む。
 * m3u は「1行に1曲の場所」が書いてあるだけの文字のファイル。# で始まる行は、題名などのおまけ。
 * 書かれている場所はスマホによってまちまち（/storage/emulated/0/Music/… や ../曲.mp3 や file:///…）なので、
 * 「ファイル名と、その上のフォルダ名が、できるだけ多く同じ曲」を一覧から探して結びつける。
 */
object Playlist {
    /** プレイリストの中の曲の場所を、上から順に取り出す */
    fun parseM3u(text: String): List<String> =
        text.removePrefix("﻿").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                var s = line
                if (s.startsWith("file://")) s = decodePercent(s.removePrefix("file://"))
                s.replace('\\', '/')
            }

    /** %E6%9B%B2 のような書き方を、もとの文字に戻す（UTF-8） */
    fun decodePercent(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { out.write(hex); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString("UTF-8")
    }

    private fun segments(path: String): List<String> =
        path.replace('\\', '/').substringAfterLast(':').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.map { it.lowercase() }

    /**
     * プレイリストの曲を、手元の曲の一覧（場所の文字）に結びつける。
     * @param library 手元の曲の場所（例：「primary:Music/アルバム/01 曲.mp3」）
     * @return プレイリストの1曲ごとに、見つかった library の番号（見つからなければ null）
     */
    fun match(entries: List<String>, library: List<String>): List<Int?> {
        val libSegs = library.map(::segments)
        val byName = HashMap<String, MutableList<Int>>()
        libSegs.forEachIndexed { i, segs -> segs.lastOrNull()?.let { byName.getOrPut(it) { ArrayList() } += i } }
        return entries.map { entry ->
            val segs = segments(entry)
            val candidates = segs.lastOrNull()?.let { byName[it] } ?: return@map null
            // うしろから同じフォルダ名が続く数が、いちばん多い曲
            candidates.maxByOrNull { i ->
                val l = libSegs[i]
                var k = 0
                while (k < segs.size && k < l.size && segs[segs.size - 1 - k] == l[l.size - 1 - k]) k++
                k
            }
        }
    }
}
