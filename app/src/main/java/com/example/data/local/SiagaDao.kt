package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SiagaDao {
    @Query("SELECT * FROM registered_phones ORDER BY isMonitored DESC, addedAt DESC")
    fun observeRegisteredPhones(): Flow<List<RegisteredPhoneEntity>>

    @Query("SELECT * FROM registered_phones WHERE isMonitored = 1")
    suspend fun getMonitoredPhonesOnce(): List<RegisteredPhoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPhone(phone: RegisteredPhoneEntity)

    @Update
    suspend fun updatePhone(phone: RegisteredPhoneEntity)

    @Query("UPDATE registered_phones SET isMonitored = :isMonitored WHERE macAddress = :macAddress")
    suspend fun setPhoneMonitored(macAddress: String, isMonitored: Boolean)

    @Query("UPDATE registered_phones SET lastSeenTimestamp = :timestamp, lastRssi = :rssi WHERE macAddress = :macAddress")
    suspend fun markPhoneSeen(macAddress: String, timestamp: Long, rssi: Int?)

    @Query("DELETE FROM registered_phones WHERE macAddress = :macAddress")
    suspend fun deletePhone(macAddress: String)

    @Query("SELECT * FROM check_logs ORDER BY timestamp DESC LIMIT 50")
    fun observeCheckLogs(): Flow<List<CheckLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckLog(log: CheckLogEntity)

    @Query("DELETE FROM check_logs")
    suspend fun clearCheckLogs()
}
