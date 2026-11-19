package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RegisteredPhoneDao {

    @Query("SELECT * FROM registered_phones ORDER BY isMonitored DESC, addedAt DESC")
    fun observeAllPhones(): Flow<List<RegisteredPhoneEntity>>

    @Query("SELECT * FROM registered_phones WHERE isMonitored = 1 ORDER BY addedAt DESC")
    fun observeMonitoredPhones(): Flow<List<RegisteredPhoneEntity>>

    @Query("SELECT macAddress FROM registered_phones WHERE isMonitored = 1 ORDER BY addedAt DESC")
    fun observeMonitoredMacAddresses(): Flow<List<String>>

    @Query("SELECT * FROM registered_phones WHERE isMonitored = 1 ORDER BY addedAt DESC")
    suspend fun getMonitoredPhonesOnce(): List<RegisteredPhoneEntity>

    @Query("SELECT * FROM registered_phones WHERE macAddress = :macAddress LIMIT 1")
    suspend fun getPhoneByMac(macAddress: String): RegisteredPhoneEntity?

    @Query("SELECT COUNT(*) FROM registered_phones")
    suspend fun getRegisteredCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPhone(phone: RegisteredPhoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPhones(phones: List<RegisteredPhoneEntity>)

    @Update
    suspend fun updatePhone(phone: RegisteredPhoneEntity)

    @Query(
        """
        UPDATE registered_phones
        SET deviceName = :deviceName,
            ownerLabel = :ownerLabel,
            category = :category,
            rssiThreshold = :rssiThreshold,
            isMonitored = :isMonitored
        WHERE macAddress = :macAddress
        """
    )
    suspend fun updatePhoneMetadata(
        macAddress: String,
        deviceName: String,
        ownerLabel: String,
        category: String,
        rssiThreshold: Int,
        isMonitored: Boolean
    )

    @Query("UPDATE registered_phones SET isMonitored = :isMonitored WHERE macAddress = :macAddress")
    suspend fun setPhoneMonitored(macAddress: String, isMonitored: Boolean)

    @Query("UPDATE registered_phones SET lastSeenTimestamp = :timestamp, lastRssi = :rssi WHERE macAddress = :macAddress")
    suspend fun markPhoneSeen(macAddress: String, timestamp: Long, rssi: Int?)

    @Query("DELETE FROM registered_phones WHERE macAddress = :macAddress")
    suspend fun deletePhoneByMac(macAddress: String)

    @Query("DELETE FROM registered_phones")
    suspend fun deleteAllPhones()
}
