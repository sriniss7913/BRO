package com.example.bro.agent

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.gson.Gson

@Entity(tableName = "active_task_checkpoint")
data class TaskCheckpointEntity(
    @PrimaryKey val id: Int = SINGLE_ACTIVE_TASK_ID,
    val payloadJson: String
) {
    companion object {
        const val SINGLE_ACTIVE_TASK_ID = 1
    }
}

@Dao
interface TaskCheckpointDao {
    @Query("SELECT * FROM active_task_checkpoint WHERE id = :id LIMIT 1")
    suspend fun load(id: Int = TaskCheckpointEntity.SINGLE_ACTIVE_TASK_ID): TaskCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: TaskCheckpointEntity)

    @Query("DELETE FROM active_task_checkpoint WHERE id = :id")
    suspend fun clear(id: Int = TaskCheckpointEntity.SINGLE_ACTIVE_TASK_ID)
}

@Database(
    entities = [TaskCheckpointEntity::class],
    version = 1,
    exportSchema = true
)
abstract class TaskCheckpointDatabase : RoomDatabase() {
    abstract fun checkpointDao(): TaskCheckpointDao

    companion object {
        @Volatile
        private var instance: TaskCheckpointDatabase? = null

        fun get(context: Context): TaskCheckpointDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TaskCheckpointDatabase::class.java,
                    "bro_task_checkpoints.db"
                ).build().also { instance = it }
            }
    }
}

object TaskCheckpointStore {
    private val gson = Gson()

    suspend fun load(context: Context): TaskCheckpoint? {
        val entity = TaskCheckpointDatabase.get(context).checkpointDao().load() ?: return null
        return try {
            gson.fromJson(entity.payloadJson, TaskCheckpoint::class.java)
        } catch (error: Exception) {
            throw IllegalStateException("The saved task checkpoint could not be read.", error)
        }
    }

    suspend fun save(context: Context, checkpoint: TaskCheckpoint) {
        val payload = gson.toJson(
            checkpoint.copy(updatedAtMillis = System.currentTimeMillis())
        )
        TaskCheckpointDatabase.get(context).checkpointDao().save(
            TaskCheckpointEntity(payloadJson = payload)
        )
    }

    suspend fun clear(context: Context) {
        TaskCheckpointDatabase.get(context).checkpointDao().clear()
    }
}
