package com.chill.run.data

/**
 * データベースの「引っ越し」で実行する命令。
 * ここに書いた形が、アプリの中の表の定義とずれていると起動できなくなるので、
 * MigrationTest が自動で突き合わせる。
 */
object MigrationSql {
    /** 段階1 の runs 表にあった項目 */
    val RUNS_V1_COLUMNS = setOf(
        "id", "startTime", "endTime", "type", "goalKind", "goalValue", "distanceM", "movingMs", "gainM", "lossM",
        "steps", "calories", "elevationSource", "startAltSource", "altOffset", "hcSynced", "note",
    )

    val V1_TO_2 = listOf(
        "ALTER TABLE `runs` ADD COLUMN `elevProfile` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `runs` ADD COLUMN `shape` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `runs` ADD COLUMN `courseId` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `runs` ADD COLUMN `typeSuggestion` TEXT NOT NULL DEFAULT ''",
        "CREATE TABLE IF NOT EXISTS `courses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `shape` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `laps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `kind` TEXT NOT NULL, `rep` INTEGER NOT NULL, `distanceM` REAL NOT NULL, `movingMs` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_laps_runId` ON `laps` (`runId`)",
        "CREATE TABLE IF NOT EXISTS `interval_menus` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `warmupSec` INTEGER NOT NULL, `runByDistance` INTEGER NOT NULL, `runAmount` REAL NOT NULL, `restSec` INTEGER NOT NULL, `repeats` INTEGER NOT NULL, `cooldownSec` INTEGER NOT NULL)",
    )
}
