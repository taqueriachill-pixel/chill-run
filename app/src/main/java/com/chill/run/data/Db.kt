package com.chill.run.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 1回のラン */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    /** 0 のあいだは「記録中」 */
    val endTime: Long = 0,
    /** ROAD か TRAIL */
    val type: String,
    val goalKind: String = "FREE",
    val goalValue: Double = 0.0,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val gainM: Double = 0.0,
    val lossM: Double = 0.0,
    val steps: Int = 0,
    val calories: Double = 0.0,
    /** 高さの測り方：「気圧」か「GPS」 */
    val elevationSource: String = "",
    /** 開始地点の標高の合わせ方：「地理院」か「GPS」 */
    val startAltSource: String = "",
    /** 地点の alt にこの値を足すと標高になる */
    val altOffset: Double = 0.0,
    val hcSynced: Boolean = false,
    val note: String = "",
    // ---- ここから段階2で追加 ----
    /** 一覧に出す小さな標高グラフ用の値（カンマ区切り） */
    @ColumnInfo(defaultValue = "") val elevProfile: String = "",
    /** ルートの形（50m ごとの点）。同じコースかどうかを比べるのに使う */
    @ColumnInfo(defaultValue = "") val shape: String = "",
    /** いつものコースの番号。0 はコースなし */
    @ColumnInfo(defaultValue = "0") val courseId: Long = 0,
    /** 自動の判定が選んだ種類と違うときの提案（"TRAIL" か "ROAD"）。なければ空 */
    @ColumnInfo(defaultValue = "") val typeSuggestion: String = "",
    // ---- ここから段階5で追加 ----
    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Query("SELECT * FROM tracks ORDER BY title")
    suspend fun tracks(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY title")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun track(id: Long): TrackEntity?

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    @Query("DELETE FROM tracks")
    suspend fun deleteAllTracks()

    /** 学習用：これまでの 1km ごとの記録（ペースとケイデンスを出すのに使う） */
    @Query("SELECT * FROM splits WHERE distanceM >= 999 AND steps > 0 ORDER BY id DESC LIMIT 300")
    suspend fun recentFullSplits(): List<SplitEntity>

    // ---- ここから段階4で追加 ----
    @ColumnInfo(defaultValue = "0") val avgHr: Int = 0,
    @ColumnInfo(defaultValue = "0") val maxHr: Int = 0,
    /** 心拍数の取り方：「リアルタイム」か「ヘルスコネクト」。取れていなければ空 */
    @ColumnInfo(defaultValue = "") val hrSource: String = "",
    /** 今日のねらい："JOG"（ゆっくりジョグ）、"TEMPO"（ペース走）、なければ空 */
    @ColumnInfo(defaultValue = "") val aim: String = "",
)

/** いつものコース */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** このコースの種類が決まっていれば "ROAD" か "TRAIL"。決まっていなければ空 */
    val type: String = "",
    val shape: String,
)

/** インターバルで走った1区間 */
@Entity(tableName = "laps", indices = [Index("runId")])
data class LapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val idx: Int,
    /** WARMUP / RUN / REST / COOLDOWN */
    val kind: String,
    val rep: Int,
    val distanceM: Double,
    val movingMs: Long,
)

/** 保存したインターバルのメニュー */
@Entity(tableName = "interval_menus")
data class IntervalMenuEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val warmupSec: Int,
    val runByDistance: Boolean,
    val runAmount: Double,
    val restSec: Int,
    val repeats: Int,
    val cooldownSec: Int,
)

/** ルートの1地点 */
@Entity(tableName = "points", indices = [Index("runId")])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val accuracy: Float,
    val distM: Double,
    val movingMs: Long,
    val cadence: Int?,
    /** 心拍数（段階4で追加）。取れていなければ null */
    val hr: Int? = null,
)

/** 1km ごとの区切り */
@Entity(tableName = "splits", indices = [Index("runId")])
data class SplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val idx: Int,
    val distanceM: Double,
    val movingMs: Long,
    val gainM: Double,
    val lossM: Double,
    val steps: Int,
    /** 平均の心拍数（段階4で追加）。取れていなければ 0 */
    @ColumnInfo(defaultValue = "0") val avgHr: Int = 0,
)

/** 走っている最中に残した、声のメモ・写真・動画 */
@Entity(tableName = "media", indices = [Index("runId")])
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    /** MEMO / PHOTO / VIDEO */
    val kind: String,
    val time: Long,
    /** 残した地点。GPS がまだ取れていなかったときは null */
    val lat: Double?,
    val lon: Double?,
    val distM: Double,
    /** アプリの中の保存場所からの相対パス */
    val file: String,
    /** 声のメモを文字にしたもの（あとから直せる） */
    val text: String = "",
    /** 0=まだ文字にしていない 1=文字にできた 2=文字にできなかった */
    val textState: Int = 0,
    val durationMs: Long = 0,
)

/** 音楽の曲（段階5で追加） */
@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 曲のファイルの場所 */
    val uri: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0,
    /** テンポ。まだ調べていない・調べられなかったときは 0 */
    val bpm: Double = 0.0,
    /** 手で直した BPM か（手で直したものは、自動では書き換えない） */
    val bpmManual: Boolean = false,
    /** 0=まだ調べていない 1=調べた 2=調べられなかった */
    val bpmState: Int = 0,
    /** ランで使うか */
    val enabled: Boolean = true,
)

@Dao
interface RunDao {
    @Insert
    suspend fun insertRun(run: RunEntity): Long

    @Update
    suspend fun updateRun(run: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun getRun(id: Long): RunEntity?

    @Query("SELECT * FROM runs WHERE endTime > 0 ORDER BY startTime DESC")
    fun observeRuns(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE endTime = 0")
    suspend fun unfinishedRuns(): List<RunEntity>

    @Insert
    suspend fun insertPoint(point: PointEntity)

    @Query("SELECT * FROM points WHERE runId = :runId ORDER BY time, id")
    suspend fun points(runId: Long): List<PointEntity>

    @Insert
    suspend fun insertSplits(splits: List<SplitEntity>)

    @Query("SELECT * FROM splits WHERE runId = :runId ORDER BY idx")
    suspend fun splits(runId: Long): List<SplitEntity>

    @Query("DELETE FROM runs WHERE id = :runId")
    suspend fun deleteRunRow(runId: Long)

    @Query("DELETE FROM points WHERE runId = :runId")
    suspend fun deletePoints(runId: Long)

    @Query("DELETE FROM splits WHERE runId = :runId")
    suspend fun deleteSplits(runId: Long)

    // ---- ここから段階2で追加 ----
    @Query("SELECT * FROM runs WHERE endTime > 0 ORDER BY startTime DESC")
    suspend fun finishedRuns(): List<RunEntity>

    @Query("SELECT * FROM runs WHERE endTime > 0 AND courseId = :courseId ORDER BY startTime")
    suspend fun runsOfCourse(courseId: Long): List<RunEntity>

    @Query("UPDATE runs SET courseId = :courseId WHERE id = :runId")
    suspend fun setCourse(runId: Long, courseId: Long)

    @Insert
    suspend fun insertCourse(course: CourseEntity): Long

    @Update
    suspend fun updateCourse(course: CourseEntity)

    @Query("SELECT * FROM courses")
    suspend fun courses(): List<CourseEntity>

    @Query("SELECT * FROM courses")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun course(id: Long): CourseEntity?

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourse(id: Long)

    @Insert
    suspend fun insertLaps(laps: List<LapEntity>)

    @Query("SELECT * FROM laps WHERE runId = :runId ORDER BY idx")
    suspend fun laps(runId: Long): List<LapEntity>

    @Query("DELETE FROM laps WHERE runId = :runId")
    suspend fun deleteLaps(runId: Long)

    // ---- ここから段階4で追加 ----
    @Query("UPDATE points SET hr = :hr WHERE id = :id")
    suspend fun setPointHr(id: Long, hr: Int?)

    @Update
    suspend fun updateSplit(split: SplitEntity)

    // ---- ここから段階3で追加 ----
    @Insert
    suspend fun insertMedia(media: MediaEntity): Long

    @Update
    suspend fun updateMedia(media: MediaEntity)

    @Query("SELECT * FROM media WHERE runId = :runId ORDER BY time")
    suspend fun media(runId: Long): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media WHERE runId = :runId")
    suspend fun mediaCount(runId: Long): Int

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteMediaRow(id: Long)

    @Query("DELETE FROM media WHERE runId = :runId")
    suspend fun deleteMediaOfRun(runId: Long)

    @Insert
    suspend fun insertMenu(menu: IntervalMenuEntity): Long

    @Update
    suspend fun updateMenu(menu: IntervalMenuEntity)

    @Query("SELECT * FROM interval_menus ORDER BY id")
    fun observeMenus(): Flow<List<IntervalMenuEntity>>

    @Query("SELECT * FROM interval_menus WHERE id = :id")
    suspend fun menu(id: Long): IntervalMenuEntity?

    @Query("DELETE FROM interval_menus WHERE id = :id")
    suspend fun deleteMenu(id: Long)
}

suspend fun RunDao.deleteRun(runId: Long) {
    val courseId = getRun(runId)?.courseId ?: 0
    deletePoints(runId)
    deleteSplits(runId)
    deleteLaps(runId)
    deleteMediaOfRun(runId)
    deleteRunRow(runId)
    // そのコースのランが1つもなくなったら、コースも片づける
    if (courseId > 0 && runsOfCourse(courseId).isEmpty()) deleteCourse(courseId)
}

@Database(
    entities = [
        RunEntity::class, PointEntity::class, SplitEntity::class, CourseEntity::class, LapEntity::class,
        IntervalMenuEntity::class, MediaEntity::class, TrackEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "chillrun.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
        }
    }
}

/** 段階1 → 段階2 の「引っ越し」。これまでの記録を残したまま、新しい項目と表を足す */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.V1_TO_2.forEach { db.execSQL(it) }
    }
}

/** 段階2 → 段階3 の「引っ越し」。声のメモ・写真・動画の表を足す */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.V2_TO_3.forEach { db.execSQL(it) }
    }
}

/** 段階3 → 段階4 の「引っ越し」。心拍数の項目を足す */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.V3_TO_4.forEach { db.execSQL(it) }
    }
}

/** 段階4 → 段階5 の「引っ越し」。音楽の曲の表を足す */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.V4_TO_5.forEach { db.execSQL(it) }
    }
}
