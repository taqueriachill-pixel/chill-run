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
 *
 * 版を上げるときは、下の4か所（版の番号、命令、新しい表、前の版の項目の数）を書きかえる。
 */
class MigrationTest {
    private val version = 6
    private val statements = MigrationSql.V5_TO_6
    private val newTables = emptySet<String>()

    /** 前の版（5）の、今回は形を変えていない表の項目の数。項目を足したのに引っ越しを書き忘れると、ここで気づける */
    private val before = mapOf(
        "points" to 11, "splits" to 9, "courses" to 4, "laps" to 7, "interval_menus" to 8, "media" to 11, "tracks" to 9,
    )

    /** 今回、項目を足した表と、前の版にあった項目 */
    private val altered = mapOf("runs" to MigrationSql.RUNS_V5_COLUMNS)

    private fun currentSchema(): JSONObject {
        val dir = listOf("schemas", "app/schemas").map { File(it, "com.chill.run.data.AppDb") }.first { it.exists() }
        val latest = dir.listFiles()!!.filter { it.name.endsWith(".json") }.maxBy { it.name.removeSuffix(".json").toInt() }
        return JSONObject(latest.readText()).getJSONObject("database")
    }

    private fun norm(sql: String) = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun latest_migration_matches_schema() {
        val db = currentSchema()
        assertEquals("版を上げたら、このテストも新しい引っ越しに合わせて書きかえてください", version, db.getInt("version"))
        val wanted = statements.map(::norm).toSet()
        val used = HashSet<String>()
        val entities = db.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            val fields = e.getJSONArray("fields")
            when {
                table in newTables -> {
                    val create = norm(e.getString("createSql").replace("\${TABLE_NAME}", table))
                    assertTrue("表 $table の作り方が違います。\n設計図: $create", create in wanted)
                    used += create
                    val indices = e.optJSONArray("indices")
                    if (indices != null) for (k in 0 until indices.length()) {
                        val idx = norm(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
                        assertTrue("表 $table の索引が違います。\n設計図: $idx", idx in wanted)
                        used += idx
                    }
                }
                table in altered -> {
                    for (k in 0 until fields.length()) {
                        val f = fields.getJSONObject(k)
                        val name = f.getString("columnName")
                        if (name in altered.getValue(table)) continue
                        val notNull = f.optBoolean("notNull", false)
                        val default = if (f.has("defaultValue")) " DEFAULT ${f.getString("defaultValue")}" else ""
                        val alter = "ALTER TABLE `$table` ADD COLUMN `$name` ${f.getString("affinity")}${if (notNull) " NOT NULL" else ""}$default"
                        assertTrue("$table 表に足す項目 $name の命令が違います。\n設計図: $alter", alter in wanted)
                        assertTrue("空にできない項目 $name をあとから足すには、初期値が必要です", !notNull || f.has("defaultValue"))
                        used += alter
                    }
                }
                else -> {
                    assertTrue("知らない表 $table があります。引っ越しを書き忘れていませんか", table in before)
                    assertEquals("表 $table の項目の数が変わっています。引っ越しを書いてください", before.getValue(table), fields.length())
                }
            }
        }
        assertEquals("設計図にない命令が、引っ越しに入っています", wanted, used)
        assertEquals(before.size + newTables.size + altered.size, entities.length())
    }

    /** バックアップが、いまの表を1つも取りこぼしていないか */
    @Test
    fun backup_covers_every_table() {
        val db = currentSchema()
        val entities = db.getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
        val source = listOf("src/main/java/com/chill/run/data/Backup.kt", "app/src/main/java/com/chill/run/data/Backup.kt")
            .map(::File).first { it.exists() }.readText()
        val listed = Regex("TABLES = listOf\\(([^)]*)\\)").find(source)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        assertEquals("バックアップの表の一覧（Backup.TABLES）を、いまの表に合わせてください", tables, listed)
        val backupVersion = Regex("DB_VERSION = (\\d+)").find(source)!!.groupValues[1].toInt()
        assertEquals("Backup.DB_VERSION を、データベースの版に合わせてください", db.getInt("version"), backupVersion)
    }
}
