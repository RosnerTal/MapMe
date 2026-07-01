package com.talapp.mapme.data

import kotlinx.coroutines.flow.Flow

class WalkRepository(private val walkDao: WalkDao) {
    val allWalks: Flow<List<Walk>> = walkDao.getAllWalks()

    suspend fun insertWalk(walk: Walk): Long {
        return walkDao.insertWalk(walk)
    }

    suspend fun deleteWalk(walk: Walk) {
        walkDao.deleteWalk(walk)
    }

    suspend fun deleteWalkById(id: Long) {
        walkDao.deleteWalkById(id)
    }

    suspend fun getWalkById(id: Long): Walk? {
        return walkDao.getWalkById(id)
    }

    suspend fun getUnsyncedWalks(): List<Walk> {
        return walkDao.getUnsyncedWalks()
    }

    suspend fun markWalkSynced(id: Long) {
        walkDao.markWalkSynced(id)
    }
}
