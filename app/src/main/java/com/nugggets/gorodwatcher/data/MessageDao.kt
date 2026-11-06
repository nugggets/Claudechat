package com.nugggets.gorodwatcher.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Query("SELECT * FROM messages ORDER BY publishDate DESC")
    fun getAllMessages(): LiveData<List<Message>>

    @Query("SELECT * FROM messages WHERE themeId = :themeId ORDER BY publishDate DESC")
    fun getByTheme(themeId: Int): LiveData<List<Message>>

    @Query("SELECT * FROM messages WHERE statusCode = :status ORDER BY publishDate DESC")
    fun getByStatus(status: String): LiveData<List<Message>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    fun getById(id: Int): LiveData<Message>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM messages")
    fun getCount(): LiveData<Int>

    @Query("SELECT DISTINCT themeName FROM messages ORDER BY themeName")
    fun getThemes(): LiveData<List<String>>
}
