package com.chill.run.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 全データのバックアップ（書き出し・読み込み）。
 * 1つの zip ファイルに、記録（データベースの中身）、設定、励ましの言葉、
 * 声のメモ・写真・動画（入れるかどうかは選べる）をまとめる。地図の一時保存は入れない（取り直せるため）。
 */
object Backup {
    private val TABLES = listOf("runs", "points", "splits", "courses", "laps", "interval_menus", "media", "tracks")
    private const val DB_VERSION = 6

    class Summary(val runs: Int, val files: Int)

    fun export(context: Context, uri: Uri, includeMedia: Boolean): Summary {
        val db = AppDb.get(context).openHelper.readableDatabase
        var runs = 0
        var files = 0
        val out = context.contentResolver.openOutputStream(uri) ?: error("書き出し先を開けませんでした")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            val w = JsonWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            w.beginObject()
            w.name("app").value("chillrun")
            w.name("dbVersion").value(DB_VERSION.toLong())
            w.name("createdAt").value(System.currentTimeMillis())
            w.name("settings")
            writeSettings(w, context)
            w.name("phrases")
            if (PhraseStore.isCustom(context)) w.value(PhraseStore.currentText(context)) else w.nullValue()
            w.name("tables").beginObject()
            for (table in TABLES) {
                w.name(table).beginArray()
                db.query("SELECT * FROM `$table`").use { c ->
                    val names = c.columnNames
                    while (c.moveToNext()) {
                        w.beginObject()
                        for (i in names.indices) {
                            w.name(names[i])
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> w.nullValue()
                                Cursor.FIELD_TYPE_INTEGER -> w.value(c.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> w.value(c.getDouble(i))
                                else -> w.value(c.getString(i))
                            }
                        }
                        w.endObject()
                        if (table == "runs") runs++
                    }
                }
                w.endArray()
            }
            w.endObject()
            w.endObject()
            w.flush()
            zip.closeEntry()

            if (includeMedia) {
                val root = File(context.filesDir, "media")
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("media/" + f.relativeTo(root).path.replace(File.separatorChar, '/')))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    files++
                }
            }
        }
        return Summary(runs, files)
    }

    private fun writeSettings(w: JsonWriter, context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        w.beginObject()
        for ((key, value) in prefs.all) {
            w.name(key).beginObject()
            when (value) {
                is Boolean -> { w.name("t").value("b"); w.name("v").value(value) }
                is Int -> { w.name("t").value("i"); w.name("v").value(value.toLong()) }
                is Long -> { w.name("t").value("l"); w.name("v").value(value) }
                is Float -> { w.name("t").value("f"); w.name("v").value(value.toDouble()) }
                is String -> { w.name("t").value("s"); w.name("v").value(value) }
                else -> { w.name("t").value("x") }
            }
            w.endObject()
        }
        w.endObject()
    }

    /**
     * バックアップを読み込む。いまの記録・設定は、すべて置きかわる。
     * 中身がこのアプリのバックアップでないとき、新しすぎる版のものは、何も変えずに例外を投げる。
     * 読み込んだあとは、アプリを開き直す必要がある。
     */
    fun import(context: Context, uri: Uri): Summary {
        val db = AppDb.get(context).openHelper.writableDatabase
        var runs = 0
        var files = 0
        var sawJson = false
        val input = context.contentResolver.openInputStream(uri) ?: error("ファイルを開けませんでした")
        val mediaRoot = File(context.filesDir, "media")
        val staging = File(context.cacheDir, "restore_media").also { it.deleteRecursively(); it.mkdirs() }
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "backup.json") {
                    runs = readJson(context, db, JsonReader(InputStreamReader(zip, Charsets.UTF_8)))
                    sawJson = true
                } else if (name.startsWith("media/") && !entry.isDirectory && !name.contains("..")) {
                    // いったん別の場所に取り出して、最後に入れかえる
                    val target = File(staging, name.removePrefix("media/"))
                    if (target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        files++
                    }
                }
                entry = zip.nextEntry
            }
        }
        if (!sawJson) {
            staging.deleteRecursively()
            error("このファイルは、Chill Run のバックアップではありません")
        }
        if (files > 0) {
            mediaRoot.deleteRecursively()
            mediaRoot.parentFile?.mkdirs()
            if (!staging.renameTo(mediaRoot)) {
                staging.copyRecursively(mediaRoot, overwrite = true)
                staging.deleteRecursively()
            }
        } else staging.deleteRecursively()
        return Summary(runs, files)
    }

    private fun readJson(context: Context, db: androidx.sqlite.db.SupportSQLiteDatabase, r: JsonReader): Int {
        var runs = 0
        var appOk = false
        var settings: Map<String, Pair<String, Any?>>? = null
        var phrases: String? = null
        var tablesDone = false
        db.beginTransaction()
        try {
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "app" -> appOk = r.nextString() == "chillrun"
                    "dbVersion" -> {
                        val v = r.nextInt()
                        require(v <= DB_VERSION) { "このバックアップは、新しい版の Chill Run で作られています。アプリを新しくしてから読み込んでください" }
                    }
                    "settings" -> settings = readSettings(r)
                    "phrases" -> if (r.peek() == JsonToken.NULL) r.nextNull() else phrases = r.nextString()
                    "tables" -> {
                        require(appOk) { "このファイルは、Chill Run のバックアップではありません" }
                        for (t in TABLES) db.execSQL("DELETE FROM `$t`")
                        r.beginObject()
                        while (r.hasNext()) {
                            val table = r.nextName()
                            if (table !in TABLES) { r.skipValue(); continue }
                            r.beginArray()
                            while (r.hasNext()) {
                                val values = ContentValues()
                                r.beginObject()
                                while (r.hasNext()) {
                                    val col = r.nextName()
                                    when (r.peek()) {
                                        JsonToken.NULL -> { r.nextNull(); values.putNull(col) }
                                        JsonToken.NUMBER -> {
                                            val s = r.nextString()
                                            s.toLongOrNull()?.let { values.put(col, it) } ?: values.put(col, s.toDouble())
                                        }
                                        JsonToken.BOOLEAN -> values.put(col, if (r.nextBoolean()) 1 else 0)
                                        else -> values.put(col, r.nextString())
                                    }
                                }
                                r.endObject()
                                db.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values)
                                if (table == "runs") runs++
                            }
                            r.endArray()
                        }
                        r.endObject()
                        tablesDone = true
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            require(appOk && tablesDone) { "このファイルは、Chill Run のバックアップではありません" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // 記録が入ったあとで、設定と言葉を戻す
        settings?.let { map ->
            val e = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
            for ((key, tv) in map) {
                val v = tv.second
                when (tv.first) {
                    "b" -> e.putBoolean(key, v as Boolean)
                    "i" -> e.putInt(key, (v as Number).toInt())
                    "l" -> e.putLong(key, (v as Number).toLong())
                    "f" -> e.putFloat(key, (v as Number).toFloat())
                    "s" -> e.putString(key, v as String)
                }
            }
            e.commit()
        }
        val custom = File(context.filesDir, "phrases.json")
        if (phrases != null) custom.writeText(phrases) else custom.delete()
        return runs
    }

    private fun readSettings(r: JsonReader): Map<String, Pair<String, Any?>> {
        val out = LinkedHashMap<String, Pair<String, Any?>>()
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            var type = "x"
            var value: Any? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "t" -> type = r.nextString()
                    "v" -> value = when (r.peek()) {
                        JsonToken.BOOLEAN -> r.nextBoolean()
                        JsonToken.NUMBER -> r.nextDouble()
                        JsonToken.NULL -> { r.nextNull(); null }
                        else -> r.nextString()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (value != null && type != "x") out[key] = type to value
        }
        r.endObject()
        return out
    }
}
