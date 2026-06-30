package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.CharacterVoice
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterVoiceDao {

    @Query("SELECT * FROM character_voices WHERE bookId = :bookId ORDER BY isNarrator DESC, characterName ASC")
    fun getByBookId(bookId: Long): List<CharacterVoice>

    @Query("SELECT * FROM character_voices WHERE bookId = :bookId ORDER BY isNarrator DESC, characterName ASC")
    fun flowByBookId(bookId: Long): Flow<List<CharacterVoice>>

    @Query("SELECT * FROM character_voices WHERE bookId = :bookId AND characterName = :characterName")
    fun get(bookId: Long, characterName: String): CharacterVoice?

    @Query("SELECT * FROM character_voices WHERE bookId = :bookId AND isNarrator = 1 LIMIT 1")
    fun getNarrator(bookId: Long): CharacterVoice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg voices: CharacterVoice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(voices: List<CharacterVoice>)

    @Update
    fun update(vararg voices: CharacterVoice)

    @Delete
    fun delete(vararg voices: CharacterVoice)

    @Query("DELETE FROM character_voices WHERE bookId = :bookId")
    fun deleteByBookId(bookId: Long)

    @Query("DELETE FROM character_voices WHERE bookId = :bookId AND characterName = :characterName")
    fun delete(bookId: Long, characterName: String)

    @Query("SELECT COUNT(*) FROM character_voices WHERE bookId = :bookId")
    fun countByBookId(bookId: Long): Int
}
