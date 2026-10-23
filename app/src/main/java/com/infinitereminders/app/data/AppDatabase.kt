package com.infinitereminders.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Reminder::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ReminderDao

    companion object {
        // Deliberately NO fallbackToDestructiveMigration(): reminders must never be wiped silently.
        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "infinite-reminders.db").build()

        fun inMemory(ctx: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(ctx.applicationContext, AppDatabase::class.java).build()
    }
}
