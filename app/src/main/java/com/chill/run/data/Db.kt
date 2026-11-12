package com.chill.run.data

import android.content.Context
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
}

suspend fun RunDao.deleteRun(runId: Long) {
    deletePoints(runId)
    deleteSplits(runId)
    deleteRunRow(runId)
}

@Database(entities = [RunEntity::class, PointEntity::class, SplitEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "chillrun.db")
                .build().also { instance = it }
        }
    }
}
