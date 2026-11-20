package com.thumbtrek.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One row per app per day, accumulated. Raw pixels; converted to meters at display time. */
@Entity(tableName = "daily_scroll", primaryKeys = ["packageName", "date"])
data class DailyScroll(
    val packageName: String,
    /** yyyy-MM-dd in device timezone */
    val date: String,
    val pixels: Long,
)

data class AppTotal(val packageName: String, val pixels: Long)
data class DayTotal(val date: String, val pixels: Long)

@Dao
interface ScrollDao {
    @Query("UPDATE daily_scroll SET pixels = pixels + :pixels WHERE packageName = :pkg AND date = :date")
    suspend fun addPixels(pkg: String, date: String, pixels: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: DailyScroll): Long

    @Transaction
    suspend fun accumulate(pkg: String, date: String, pixels: Long) {
        if (addPixels(pkg, date, pixels) == 0) {
            insert(DailyScroll(pkg, date, pixels))
        }
    }

    @Query("SELECT packageName, pixels FROM daily_scroll WHERE date = :date")
    fun observeDay(date: String): Flow<List<AppTotal>>

    @Query("SELECT date, SUM(pixels) AS pixels FROM daily_scroll GROUP BY date ORDER BY date DESC")
    fun observeAllDays(): Flow<List<DayTotal>>

    @Query("SELECT date, SUM(pixels) AS pixels FROM daily_scroll GROUP BY date ORDER BY date DESC")
    suspend fun allDays(): List<DayTotal>

    /** Raw per-app-per-day rows from [startDate] (yyyy-MM-dd) onwards, for PRD §5.3 trends. */
    @Query("SELECT * FROM daily_scroll WHERE date >= :startDate ORDER BY date ASC")
    fun observeRowsSince(startDate: String): Flow<List<DailyScroll>>

    /** Lifts a stored row to [pixels] when the backup holds more; never lowers one. */
    @Query("UPDATE daily_scroll SET pixels = MAX(pixels, :pixels) WHERE packageName = :pkg AND date = :date")
    suspend fun raiseTo(pkg: String, date: String, pixels: Long): Int

    /**
     * Merges a backup in. Each app-day keeps the larger of what is stored and what the
     * backup holds, so restoring the same file twice changes nothing and restoring an old
     * file never erases newer scrolling.
     */
    @Transaction
    suspend fun restore(rows: List<DailyScroll>) {
        rows.forEach { row ->
            if (raiseTo(row.packageName, row.date, row.pixels) == 0) insert(row)
        }
    }

    /** Every row ever, for export and backup. */
    @Query("SELECT * FROM daily_scroll ORDER BY date ASC, packageName ASC")
    suspend fun allRows(): List<DailyScroll>
}

@Database(entities = [DailyScroll::class], version = 1, exportSchema = false)
abstract class ScrollDatabase : RoomDatabase() {
    abstract fun dao(): ScrollDao

    companion object {
        @Volatile
        private var instance: ScrollDatabase? = null

        fun get(context: Context): ScrollDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScrollDatabase::class.java,
                    "thumbtrek.db",
                ).build().also { instance = it }
            }
    }
}
