package com.nugggets.gorodwatcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Message::class], version = 1)
abstract class GorodDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: GorodDatabase? = null

        fun getDatabase(context: Context): GorodDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, GorodDatabase::class.java, "gorod.db")
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
