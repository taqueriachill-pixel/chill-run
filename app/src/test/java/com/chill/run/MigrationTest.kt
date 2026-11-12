package com.chill.run

import com.chill.run.data.MigrationSql
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * データベースの「引っ越し」の命令が、アプリの中の表の定義（Room が書き出した設計図）と
 * 一字一句合っているかを確かめる。ずれていると、前の版から上書きしたときに起動できなくなる。
 *
 * 設計図は「いまの版」のものしか手元にないので、いまの版で足した項目・表だけを突き合わせる。
 * 前の版までに足したものは、その版のときにこのテストで確かめ済み。
 */
class MigrationTest {
    private fun currentSchema(): JSONObject {
        val dir = listOf("schemas", "app/schemas").map { File(it, "com.chill.run.data.AppDb") }.first { it.exists() }
        val latest = dir.listFiles()!!.filter { it.name.endsWith(".json") }.maxBy { it.name.removeSuffix(".json").toInt() }
        return JSONObject(latest.readText()).getJSONObject("database")
    }

    private fun norm(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun latest_migration_matches_schema() {
        val db = currentSchema()
        assertEquals("版を上げたら、このテストも新しい引っ越しに合わせて書き足してください", 4, db.getInt("version"))
        val statements = MigrationSql.V3_TO_4.map(::norm).toSet()
        val knownTables = setOf("runs", "points", "splits", "courses", "laps", "interval_menus", "media")
        // 段階4 では項目を足していない表の、項目の数（足したのに引っ越しを書き忘れると、ここで気づける）
        val unchanged = mapOf("courses" to 4, "laps" to 7, "interval_menus" to 8, "media" to 11)

        val entities = db.getJSONArray("entities")
        val used = HashSet<String>()
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            assertTrue("知らない表 $table があります。引っ越しを書き忘れていませんか", table in knownTables)
            val fields = e.getJSONArray("fields")
            val before = MigrationSql.V3_COLUMNS[table]
            if (before == null) {
                assertEquals("表 $table の項目の数が変わっています。引っ越しを書いてください", unchanged.getValue(table), fields.length())
                continue
            }
            for (k in 0 until fields.length()) {
                val f = fields.getJSONObject(k)
                val name = f.getString("columnName")
                if (name in before) continue
                val notNull = f.getBoolean("notNull")
                val default = if (f.has("defaultValue")) " DEFAULT ${f.getString("defaultValue")}" else ""
                val alter = "ALTER TABLE `$table` ADD COLUMN `$name` ${f.getString("affinity")}${if (notNull) " NOT NULL" else ""}$default"
                assertTrue("$table 表に足す項目 $name の命令が違います。\n設計図: $alter", alter in statements)
                assertTrue("空にできない項目 $name をあとから足すには、初期値が必要です", !notNull || f.has("defaultValue"))
                used += alter
            }
        }
        assertEquals("設計図にない命令が、引っ越しに入っています", statements, used)
        assertEquals(knownTables.size, entities.length())
    }
}
