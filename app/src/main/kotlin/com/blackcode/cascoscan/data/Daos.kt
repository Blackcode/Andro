package com.blackcode.cascoscan.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun find(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE documentUri = :uri LIMIT 1")
    suspend fun findByDocument(uri: String): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)
}

@Dao
interface SheetDao {

    @Query("SELECT * FROM sheets WHERE projectId = :projectId ORDER BY pageIndex")
    fun observeForProject(projectId: String): Flow<List<SheetEntity>>

    @Query("SELECT * FROM sheets WHERE projectId = :projectId ORDER BY pageIndex")
    suspend fun forProject(projectId: String): List<SheetEntity>

    @Query("SELECT * FROM sheets WHERE id = :id")
    fun observe(id: String): Flow<SheetEntity?>

    @Query("SELECT * FROM sheets WHERE id = :id")
    suspend fun find(id: String): SheetEntity?

    @Upsert
    suspend fun upsertAll(sheets: List<SheetEntity>)

    @Update
    suspend fun update(sheet: SheetEntity)

    @Query("UPDATE sheets SET detectionState = :state, detectedAt = :at, diagnostics = :diagnostics WHERE id = :id")
    suspend fun setDetectionState(id: String, state: String, at: Long?, diagnostics: String?)
}

@Dao
interface PenetrationDao {

    @Query("SELECT * FROM penetrations WHERE sheetId = :sheetId ORDER BY displayId")
    fun observeForSheet(sheetId: String): Flow<List<PenetrationEntity>>

    @Query("SELECT * FROM penetrations WHERE sheetId = :sheetId")
    suspend fun forSheet(sheetId: String): List<PenetrationEntity>

    @Query("SELECT * FROM penetrations WHERE projectId = :projectId ORDER BY pageIndex, displayId")
    fun observeForProject(projectId: String): Flow<List<PenetrationEntity>>

    @Query("SELECT * FROM penetrations WHERE projectId = :projectId ORDER BY pageIndex, displayId")
    suspend fun forProject(projectId: String): List<PenetrationEntity>

    @Query("SELECT * FROM penetrations WHERE id = :id")
    suspend fun find(id: String): PenetrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PenetrationEntity>)

    @Upsert
    suspend fun upsert(row: PenetrationEntity)

    @Query("DELETE FROM penetrations WHERE sheetId = :sheetId AND origin = :origin")
    suspend fun deleteByOrigin(sheetId: String, origin: String)

    @Query("DELETE FROM penetrations WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Replaces the results of a previous detection run while leaving every row a human has touched.
     *
     * One transaction, because a crash between the delete and the insert would lose the sheet's
     * findings - and an audit that loses findings is worse than one that never ran.
     */
    @Transaction
    suspend fun replaceDetected(sheetId: String, machineOrigin: String, fresh: List<PenetrationEntity>) {
        deleteByOrigin(sheetId, machineOrigin)
        insertAll(fresh)
    }
}

@Dao
interface PrototypeDao {

    @Query("SELECT * FROM prototypes WHERE projectId = :projectId")
    suspend fun forProject(projectId: String): List<PrototypeEntity>

    @Query("SELECT COUNT(*) FROM prototypes WHERE projectId = :projectId")
    fun observeCount(projectId: String): Flow<Int>

    @Insert
    suspend fun insert(prototype: PrototypeEntity)

    @Query("DELETE FROM prototypes WHERE projectId = :projectId")
    suspend fun clear(projectId: String)
}

@Dao
interface AlignmentDao {

    @Upsert
    suspend fun upsert(alignment: AlignmentEntity)

    @Query("SELECT * FROM alignments WHERE referenceSheetId = :reference AND targetSheetId = :target")
    suspend fun find(reference: String, target: String): AlignmentEntity?

    @Query("SELECT * FROM alignments WHERE projectId = :projectId")
    fun observeForProject(projectId: String): Flow<List<AlignmentEntity>>
}
