package com.nugggets.gorodwatcher.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: Int,
    val themeId: Int,
    val themeName: String,
    val createdDate: Long,
    val publishDate: Long,
    val statusCode: String,
    val statusName: String,
    val text: String,
    val name: String,
    val address: String,
    val lat: Double?,
    val lon: Double?,
    val userName: String,
    val attachments: String
)
