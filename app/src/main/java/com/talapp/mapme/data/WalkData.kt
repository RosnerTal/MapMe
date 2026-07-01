package com.talapp.mapme.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class WalkPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val speed: Float = 0f
)

data class WalkPoi(
    val id: String = java.util.UUID.randomUUID().toString(),
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val text: String? = null,
    val imageBase64: String? = null
)

@Entity(tableName = "walks")
data class Walk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val totalDistanceMeters: Double,
    val totalDurationMillis: Long,
    val pointsJson: String,
    val poisJson: String = "[]",
    val isSynced: Boolean = false
)

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromWalkPointList(value: List<WalkPoint>?): String? {
        if (value == null) return null
        return gson.toJson(value)
    }

    @TypeConverter
    fun toWalkPointList(value: String?): List<WalkPoint>? {
        if (value == null) return null
        val type = object : TypeToken<List<WalkPoint>>() {}.type
        return gson.fromJson(value, type)
    }
}
