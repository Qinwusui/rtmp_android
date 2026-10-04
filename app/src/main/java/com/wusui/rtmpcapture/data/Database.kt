package com.wusui.rtmpcapture.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="servers")
data class ServerConfig(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val rtmpUrl:String,
    val stream:String,
    val authUrl:String,
    val username:String,
    val encryptedPassword:String,
    val monitorUrl:String="",
    val statusUrl:String="",
)
@Entity(tableName="sessions")
data class SessionSummary(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val serverName:String,
    val startedAt:Long,
    val endedAt:Long?=null,
    val durationSeconds:Long=0,
    val reconnects:Int=0,
    val dropped:Long=0,
    val endReason:String="采集中",
)
@Entity(tableName="events", indices=[Index("sessionId")])
data class SessionEvent(@PrimaryKey(autoGenerate=true) val id:Long=0,val sessionId:Long,val time:Long,val message:String)

@Dao
interface CaptureDao {
    @Query("SELECT * FROM servers ORDER BY id") fun servers():Flow<List<ServerConfig>>
    @Query("SELECT * FROM servers WHERE id=:id") suspend fun server(id:Long):ServerConfig?
    @Upsert suspend fun save(server:ServerConfig):Long
    @Query("DELETE FROM servers WHERE id=:id") suspend fun deleteServer(id:Long)
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 100") fun sessions():Flow<List<SessionSummary>>
    @Insert suspend fun begin(summary:SessionSummary):Long
    @Query("UPDATE sessions SET endedAt=:end,durationSeconds=:duration,reconnects=:reconnects,dropped=:dropped,endReason=:reason WHERE id=:id")
    suspend fun end(id:Long,end:Long,duration:Long,reconnects:Int,dropped:Long,reason:String)
    @Insert suspend fun event(event:SessionEvent)
    @Query("SELECT * FROM events WHERE sessionId=:id ORDER BY time LIMIT 200") suspend fun events(id:Long):List<SessionEvent>
    @Query("UPDATE sessions SET endedAt=:now,endReason='进程退出，未正常结束' WHERE endedAt IS NULL") suspend fun recoverInterrupted(now:Long)
    @Query("DELETE FROM events WHERE sessionId NOT IN (SELECT id FROM sessions ORDER BY startedAt DESC LIMIT 100)") suspend fun trimEvents()
    @Query("DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY startedAt DESC LIMIT 100)") suspend fun trimSessions()
}

@Database(entities=[ServerConfig::class,SessionSummary::class,SessionEvent::class],version=1,exportSchema=true)
abstract class CaptureDatabase:RoomDatabase() { abstract fun dao():CaptureDao }
