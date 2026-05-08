package zed.rainxch.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import zed.rainxch.core.data.local.db.entities.CacheEntryEntity

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entries WHERE `key` = :key AND expiresAt > :now LIMIT 1")
    suspend fun getValid(
        key: String,
        now: Long,
    ): CacheEntryEntity?

    @Query("SELECT * FROM cache_entries WHERE `key` = :key LIMIT 1")
    suspend fun getAny(key: String): CacheEntryEntity?

    @Query("SELECT * FROM cache_entries WHERE `key` LIKE :prefix || '%' AND expiresAt > :now")
    suspend fun getValidByPrefix(
        prefix: String,
        now: Long,
    ): List<CacheEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntryEntity)

    @Query("DELETE FROM cache_entries WHERE `key` = :key")
    suspend fun delete(key: String)

    /**
     * Conditional delete used by CacheManager when a row decoded as a
     * malformed payload — guards against evicting a freshly-put row that
     * raced in between the read and the decode-failure delete. Compares
     * `cachedAt` because (key, cachedAt) is effectively the row's version
     * stamp on this schema.
     */
    @Query("DELETE FROM cache_entries WHERE `key` = :key AND cachedAt = :cachedAt")
    suspend fun deleteIfMatches(key: String, cachedAt: Long)

    @Query("DELETE FROM cache_entries WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)

    @Query("DELETE FROM cache_entries WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM cache_entries")
    suspend fun deleteAll()
}
