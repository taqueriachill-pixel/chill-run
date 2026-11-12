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
 */
class MigrationTest {
    private fun schema(version: Int): JSONObject {
        val file = listOf("schemas", "app/schemas").map { File(it, "com.chill.run.data.AppDb/$version.json") }.first { it.exists() }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun norm(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun migration_1_to_2_matches_schema() {
        val db = schema(2)
        val statements = MigrationSql.V1_TO_2.map(::norm).toSet()
        val entities = db.getJSONArray("entities")
        val newTables = setOf("courses", "laps", "interval_menus")
        var checkedTables = 0
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            if (table in newTables) {
                val create = norm(e.getString("createSql").replace("\${TABLE_NAME}", table))
                assertTrue("表 $table の作り方が違います。\n設計図: $create", create in statements)
                val indices = e.optJSONArray("indices")
                if (indices != null) for (k in 0 until indices.length()) {
                    val idx = norm(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
                    assertTrue("表 $table の索引が違います。\n設計図: $idx", idx in statements)
                }
                checkedTables++
            }
            if (table == "runs") {
                val fields = e.getJSONArray("fields")
                var added = 0
                for (k in 0 until fields.length()) {
                    val f = fields.getJSONObject(k)
                    val name = f.getString("columnName")
                    if (name in MigrationSql.RUNS_V1_COLUMNS) continue
                    val notNull = if (f.getBoolean("notNull")) " NOT NULL" else ""
                    val default = if (f.has("defaultValue")) " DEFAULT ${f.getString("defaultValue")}" else ""
                    val alter = "ALTER TABLE `runs` ADD COLUMN `$name` ${f.getString("affinity")}$notNull$default"
                    assertTrue("runs 表に足す項目 $name の命令が違います。\n設計図: $alter", alter in statements)
                    assertTrue("あとから足す項目 $name には初期値が必要です", f.has("defaultValue"))
                    added++
                }
                assertEquals(4, added)
            }
        }
        assertEquals(newTables.size, checkedTables)
        // 段階1 からある表は、引っ越しで触らない（points と splits は形を変えていない）
        assertEquals(6, entities.length())
    }
}
