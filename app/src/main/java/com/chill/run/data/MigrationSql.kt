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

    /** 段階2 までの runs 表にある項目 */
    val RUNS_V2_COLUMNS = RUNS_V1_COLUMNS + setOf("elevProfile", "shape", "courseId", "typeSuggestion")

    val V2_TO_3 = listOf(
        "CREATE TABLE IF NOT EXISTS `media` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `time` INTEGER NOT NULL, `lat` REAL, `lon` REAL, `distM` REAL NOT NULL, `file` TEXT NOT NULL, `text` TEXT NOT NULL, `textState` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_media_runId` ON `media` (`runId`)",
    )

    /** 段階3 までの各表にある項目（段階4 で項目を足した表だけ） */
    val V3_COLUMNS = mapOf(
        "runs" to RUNS_V2_COLUMNS,
        "points" to setOf("id", "runId", "time", "lat", "lon", "alt", "accuracy", "distM", "movingMs", "cadence"),
        "splits" to setOf("id", "runId", "idx", "distanceM", "movingMs", "gainM", "lossM", "steps"),
    )

    val V3_TO_4 = listOf(
        "ALTER TABLE `runs` ADD COLUMN `avgHr` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `runs` ADD COLUMN `maxHr` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `runs` ADD COLUMN `hrSource` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `runs` ADD COLUMN `aim` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `points` ADD COLUMN `hr` INTEGER",
        "ALTER TABLE `splits` ADD COLUMN `avgHr` INTEGER NOT NULL DEFAULT 0",
    )

    val V4_TO_5 = listOf(
        "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uri` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `bpm` REAL NOT NULL, `bpmManual` INTEGER NOT NULL, `bpmState` INTEGER NOT NULL, `enabled` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_uri` ON `tracks` (`uri`)",
    )

    /** 段階5 までの runs 表にある項目 */
    val RUNS_V5_COLUMNS = RUNS_V2_COLUMNS + setOf("avgHr", "maxHr", "hrSource", "aim")

    val V5_TO_6 = listOf(
        "ALTER TABLE `runs` ADD COLUMN `best1kMs` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `runs` ADD COLUMN `best5kMs` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `runs` ADD COLUMN `best10kMs` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `runs` ADD COLUMN `memo` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `runs` ADD COLUMN `condition` INTEGER NOT NULL DEFAULT 0",
    )
}
