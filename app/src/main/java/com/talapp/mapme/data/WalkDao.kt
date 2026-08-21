package com.talapp.mapme.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WalkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWalk(walk: Walk): Long

    @Delete
    suspend fun deleteWalk(walk: Walk): Int

    @Query("DELETE FROM walks WHERE id = :id")
    suspend fun deleteWalkById(id: Long): Int

    @Query("SELECT * FROM walks ORDER BY startTime DESC")
    fun getAllWalks(): Flow<List<Walk>>

    @Query("SELECT * FROM walks ORDER BY startTime DESC")
    suspend fun getAllWalksList(): List<Walk>

    @Query("SELECT * FROM walks WHERE id = :id LIMIT 1")
    suspend fun getWalkById(id: Long): Walk?

    @Query("SELECT * FROM walks WHERE isSynced = 0")
    suspend fun getUnsyncedWalks(): List<Walk>

    @Query("UPDATE walks SET isSynced = 1 WHERE id = :id")
    suspend fun markWalkSynced(id: Long): Int
}
