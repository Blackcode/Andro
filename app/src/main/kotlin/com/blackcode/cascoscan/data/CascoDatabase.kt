package com.blackcode.cascoscan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        SheetEntity::class,
        PenetrationEntity::class,
        PrototypeEntity::class,
        AlignmentEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class CascoDatabase : RoomDatabase() {

    abstract fun projects(): ProjectDao
    abstract fun sheets(): SheetDao
    abstract fun penetrations(): PenetrationDao
    abstract fun prototypes(): PrototypeDao
    abstract fun alignments(): AlignmentDao

    companion object {
        fun create(context: Context): CascoDatabase =
            Room.databaseBuilder(context, CascoDatabase::class.java, "cascoscan.db")
                // No destructive fallback: an audit's findings are the deliverable, and silently
                // dropping them on a schema change is not an acceptable upgrade path.
                .build()
    }
}
