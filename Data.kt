package com.coldai.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@Entity(tableName = "chats")
data class Chat(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val updated: Long)

@Entity(tableName = "messages")
data class Msg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val text: String,
    val ts: Long
)

@Entity(tableName = "memories")
data class Memory(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String, val ts: Long)

@Dao
interface AppDao {
    @Query("SELECT * FROM chats ORDER BY updated DESC") fun chats(): Flow<List<Chat>>
    @Insert suspend fun insertChat(c: Chat): Long
    @Query("UPDATE chats SET updated = :u WHERE id = :id") suspend fun touch(id: Long, u: Long)
    @Query("DELETE FROM chats WHERE id = :id") suspend fun deleteChat(id: Long)
    @Query("DELETE FROM chats") suspend fun clearChats()

    @Query("SELECT * FROM messages WHERE chatId = :id ORDER BY id") fun msgs(id: Long): Flow<List<Msg>>
    @Query("SELECT * FROM messages WHERE chatId = :id ORDER BY id") suspend fun msgsOnce(id: Long): List<Msg>
    @Insert suspend fun insertMsg(m: Msg)
    @Query("DELETE FROM messages WHERE chatId = :id") suspend fun deleteMsgs(id: Long)
    @Query("DELETE FROM messages") suspend fun clearMsgs()

    @Query("SELECT * FROM memories ORDER BY id DESC") fun memories(): Flow<List<Memory>>
    @Query("SELECT * FROM memories ORDER BY id") suspend fun memoriesOnce(): List<Memory>
    @Insert suspend fun insertMemory(m: Memory)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun deleteMemory(id: Long)
    @Query("DELETE FROM memories") suspend fun clearMemories()
}

@Database(entities = [Chat::class, Msg::class, Memory::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "cold.db").build().also { inst = it }
        }
    }
}

data class AppSettings(
    val lang: String = "ru",
    val theme: String = "system",
    val apiKey: String = "",
    val model: String = "gemini-2.5-flash",
    val saveHistory: Boolean = true,
    val useMemory: Boolean = true,
    val autoSpeak: Boolean = true,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val voice: String = ""
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cold", Context.MODE_PRIVATE)

    // API-ключ хранится только в зашифрованном виде. Если шифрование недоступно, ключ не сохраняется на диск.
    private val secure: SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            ctx, "cold_secure",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) { null }

    val state = MutableStateFlow(load())

    private fun load() = AppSettings(
        lang = sp.getString("lang", "ru") ?: "ru",
        theme = sp.getString("theme", "system") ?: "system",
        apiKey = try { secure?.getString("api_key", "") ?: "" } catch (e: Exception) { "" },
        model = sp.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash",
        saveHistory = sp.getBoolean("saveHistory", true),
        useMemory = sp.getBoolean("useMemory", true),
        autoSpeak = sp.getBoolean("autoSpeak", true),
        rate = sp.getFloat("rate", 1f),
        pitch = sp.getFloat("pitch", 1f),
        voice = sp.getString("voice", "") ?: ""
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(state.value)
        state.value = n
        sp.edit()
            .putString("lang", n.lang).putString("theme", n.theme).putString("model", n.model)
            .putBoolean("saveHistory", n.saveHistory).putBoolean("useMemory", n.useMemory)
            .putBoolean("autoSpeak", n.autoSpeak).putFloat("rate", n.rate).putFloat("pitch", n.pitch)
            .putString("voice", n.voice).apply()
        try { secure?.edit()?.putString("api_key", n.apiKey)?.apply() } catch (e: Exception) { }
    }
}
