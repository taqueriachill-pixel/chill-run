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
 * 設計図は「いまの版」のものしか手元にないので、いまの版で初めて足した表だけを突き合わせる。
 * 前の版までに足した表は、その版のときにこのテストで確かめ済み。
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
        assertEquals("版を上げたら、このテストも新しい引っ越しに合わせて書き足してください", 3, db.getInt("version"))
        val statements = MigrationSql.V2_TO_3.map(::norm).toSet()
        val newTables = setOf("media")
        val knownTables = setOf("runs", "points", "splits", "courses", "laps", "interval_menus") + newTables

        val entities = db.getJSONArray("entities")
        var checked = 0
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            assertTrue("知らない表 $table があります。引っ越しを書き忘れていませんか", table in knownTables)
            if (table in newTables) {
                val create = norm(e.getString("createSql").replace("\${TABLE_NAME}", table))
                assertTrue("表 $table の作り方が違います。\n設計図: $create", create in statements)
                val indices = e.optJSONArray("indices")
                if (indices != null) for (k in 0 until indices.length()) {
                    val idx = norm(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
                    assertTrue("表 $table の索引が違います。\n設計図: $idx", idx in statements)
                }
                checked++
            }
            if (table == "runs") {
                // 今回は runs 表に項目を足していない。足したのに引っ越しを書き忘れると、ここで気づける
                val fields = e.getJSONArray("fields")
                for (k in 0 until fields.length()) {
                    val name = fields.getJSONObject(k).getString("columnName")
                    assertTrue("runs 表に新しい項目 $name があります。引っ越しを書いてください", name in MigrationSql.RUNS_V2_COLUMNS)
                }
                assertEquals(MigrationSql.RUNS_V2_COLUMNS.size, fields.length())
            }
        }
        assertEquals(newTables.size, checked)
        assertEquals(knownTables.size, entities.length())
    }
}
