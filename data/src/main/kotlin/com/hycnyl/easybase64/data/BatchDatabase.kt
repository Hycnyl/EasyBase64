package com.hycnyl.easybase64.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.hycnyl.easybase64.core.Alphabet
import com.hycnyl.easybase64.core.CodecOptions
import com.hycnyl.easybase64.core.LineEnding
import com.hycnyl.easybase64.core.Operation
import org.json.JSONArray
import org.json.JSONObject

/** Public snapshot facade used by recovery tests; persistence is normalized into bounded rows. */
data class SnapshotEntity(val id: Int = 1, val json: String)

@Entity(tableName = "batch_header")
data class HeaderEntity(@PrimaryKey val id: Int = 1, val json: String)
@Entity(tableName = "file_tasks")
data class TaskEntity(@PrimaryKey val position: Int, val json: String)
@Entity(tableName = "task_attempts")
data class AttemptEntity(@PrimaryKey val position: Int, val taskPosition: Int, val json: String)
@Entity(tableName = "output_directories")
data class DirectoryEntity(@PrimaryKey val position: Int, val json: String)

@Dao
abstract class SnapshotDao {
    @Query("SELECT * FROM batch_header WHERE id = 1")
    abstract fun readHeader(): HeaderEntity?
    @Query("SELECT * FROM file_tasks ORDER BY position")
    abstract fun readTasks(): List<TaskEntity>
    @Query("SELECT * FROM task_attempts ORDER BY position")
    abstract fun readAttempts(): List<AttemptEntity>
    @Query("SELECT * FROM output_directories ORDER BY position")
    abstract fun readDirectories(): List<DirectoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun writeHeader(header: HeaderEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun writeTasks(tasks: List<TaskEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun writeAttempts(attempts: List<AttemptEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun writeDirectories(directories: List<DirectoryEntity>)
    @Query("DELETE FROM file_tasks WHERE position >= :count")
    abstract fun truncateTasks(count: Int)
    @Query("DELETE FROM task_attempts WHERE position >= :count")
    abstract fun truncateAttempts(count: Int)
    @Query("DELETE FROM output_directories WHERE position >= :count")
    abstract fun truncateDirectories(count: Int)

    @androidx.room.Transaction
    open fun read(): SnapshotEntity? {
        val header = readHeader() ?: return null
        val json = JSONObject(header.json)
        val attempts = readAttempts().groupBy { it.taskPosition }
        json.put("tasks", JSONArray().apply {
            readTasks().forEach { task ->
                val value = JSONObject(task.json)
                value.put("attempts", JSONArray().apply { attempts[task.position]?.forEach { put(JSONObject(it.json)) } })
                put(value)
            }
        })
        json.put("outputDirectories", JSONArray().apply { readDirectories().forEach { put(JSONObject(it.json)) } })
        return SnapshotEntity(json = json.toString())
    }

    @androidx.room.Transaction
    open fun write(snapshot: SnapshotEntity) {
        val json = JSONObject(snapshot.json)
        val taskArray = json.getJSONArray("tasks")
        val directoryArray = json.optJSONArray("outputDirectories") ?: JSONArray()
        val tasks = ArrayList<TaskEntity>(taskArray.length())
        val attempts = ArrayList<AttemptEntity>()
        val directories = ArrayList<DirectoryEntity>(directoryArray.length())
        for (position in 0 until taskArray.length()) {
            val task = taskArray.getJSONObject(position)
            val taskAttempts = task.getJSONArray("attempts")
            for (i in 0 until taskAttempts.length()) attempts += AttemptEntity(attempts.size, position, taskAttempts.getJSONObject(i).toString())
            task.remove("attempts")
            tasks += TaskEntity(position, task.toString())
        }
        for (position in 0 until directoryArray.length()) directories += DirectoryEntity(position, directoryArray.getJSONObject(position).toString())
        json.remove("tasks"); json.remove("outputDirectories")
        // Compare metadata rows inside this transaction to avoid rewriting unchanged queue items.
        val previousTasks = readTasks().associateBy { it.position }
        val previousAttempts = readAttempts().associateBy { it.position }
        val previousDirectories = readDirectories().associateBy { it.position }
        writeTasks(tasks.filter { previousTasks[it.position] != it })
        writeAttempts(attempts.filter { previousAttempts[it.position] != it })
        writeDirectories(directories.filter { previousDirectories[it.position] != it })
        truncateTasks(tasks.size); truncateAttempts(attempts.size); truncateDirectories(directories.size)
        writeHeader(HeaderEntity(json = json.toString()))
    }
}

@Database(entities = [HeaderEntity::class, TaskEntity::class, AttemptEntity::class, DirectoryEntity::class], version = 1, exportSchema = false)
abstract class BatchDatabase : RoomDatabase() {
    abstract fun snapshots(): SnapshotDao
}

internal object SnapshotJson {
    fun encode(s: BatchState): String = JSONObject().apply {
        put("id", s.id); put("createdAt", s.createdAt); put("status", s.status.name)
        put("outputRoot", s.outputRoot); put("outputBatch", s.outputBatch)
        put("outputDirectories", JSONArray().apply {
            s.outputDirectories.forEach { (path, uri) -> put(JSONObject().put("path", JSONArray(path)).put("uri", uri)) }
        })
        put("scanning", s.scanning); put("warning", s.warning); put("sourceTree", s.sourceTree)
        put("recursive", s.recursive); put("options", options(s.options))
        put("tasks", JSONArray().apply { s.tasks.forEach { put(task(it)) } })
    }.toString()

    fun decode(json: String): BatchState = JSONObject(json).let { j ->
        BatchState(
            id = j.getString("id"), createdAt = j.getLong("createdAt"),
            status = BatchStatus.valueOf(j.getString("status")),
            outputRoot = j.nullable("outputRoot"), outputBatch = j.nullable("outputBatch"),
            outputDirectories = j.optJSONArray("outputDirectories")?.objects()?.associate { it.getJSONArray("path").strings() to it.getString("uri") } ?: emptyMap(),
            scanning = j.optBoolean("scanning"), warning = j.nullable("warning"),
            sourceTree = j.nullable("sourceTree"), recursive = j.optBoolean("recursive"),
            options = parseOptions(j.getJSONObject("options")),
            tasks = j.getJSONArray("tasks").objects().map { parseTask(it) }, loaded = true,
        )
    }

    private fun options(o: CodecOptions) = JSONObject().apply {
        put("operation", o.operation.name); put("alphabet", o.alphabet.name); put("padding", o.padding)
        put("wrapWidth", o.wrapWidth); put("lineEnding", o.lineEnding.name)
        put("finalNewline", o.finalNewline); put("certificate", o.certificate)
        put("ignoreWhitespace", o.ignoreWhitespace); put("recognizeCertificate", o.recognizeCertificate)
        put("allowOmittedPadding", o.allowOmittedPadding)
    }

    private fun parseOptions(j: JSONObject) = CodecOptions(
        operation = Operation.valueOf(j.getString("operation")),
        alphabet = Alphabet.valueOf(j.getString("alphabet")), padding = j.getBoolean("padding"),
        wrapWidth = j.getInt("wrapWidth"), lineEnding = LineEnding.valueOf(j.getString("lineEnding")),
        finalNewline = j.getBoolean("finalNewline"), certificate = j.getBoolean("certificate"),
        ignoreWhitespace = j.getBoolean("ignoreWhitespace"), recognizeCertificate = j.getBoolean("recognizeCertificate"),
        allowOmittedPadding = j.getBoolean("allowOmittedPadding"),
    )

    private fun task(t: FileTask) = JSONObject().apply {
        put("id", t.id); put("sourceUri", t.sourceUri); put("name", t.name); put("size", t.size)
        put("relativePath", JSONArray(t.relativePath)); put("status", t.status.name)
        put("readBytes", t.readBytes); put("writtenBytes", t.writtenBytes)
        put("outputUri", t.outputUri); put("outputName", t.outputName)
        put("errorCode", t.errorCode); put("error", t.error)
        put("attempts", JSONArray().apply { t.attempts.forEach { put(attempt(it)) } })
    }

    private fun parseTask(j: JSONObject) = FileTask(
        id = j.getString("id"), sourceUri = j.getString("sourceUri"), name = j.getString("name"),
        size = if (j.has("size") && !j.isNull("size")) j.getLong("size") else null,
        relativePath = j.getJSONArray("relativePath").strings(), status = TaskStatus.valueOf(j.getString("status")),
        readBytes = j.optLong("readBytes"), writtenBytes = j.optLong("writtenBytes"),
        outputUri = j.nullable("outputUri"), outputName = j.nullable("outputName"),
        errorCode = j.nullable("errorCode"), error = j.nullable("error"),
        attempts = j.getJSONArray("attempts").objects().map { parseAttempt(it) },
    )

    private fun attempt(a: TaskAttempt) = JSONObject().apply {
        put("id", a.id); put("status", a.status.name); put("tempUri", a.tempUri); put("finalUri", a.finalUri)
        put("phase", a.phase); put("parentUri", a.parentUri); put("plannedName", a.plannedName)
        put("startedAt", a.startedAt); put("endedAt", a.endedAt)
        put("residualUris", JSONArray(a.residualUris)); put("errorCode", a.errorCode); put("error", a.error)
    }

    private fun parseAttempt(j: JSONObject) = TaskAttempt(
        id = j.getString("id"), status = TaskStatus.valueOf(j.getString("status")),
        tempUri = j.nullable("tempUri"), finalUri = j.nullable("finalUri"), phase = j.getString("phase"),
        parentUri = j.nullable("parentUri"), plannedName = j.nullable("plannedName"),
        startedAt = j.getLong("startedAt"), endedAt = if (j.has("endedAt") && !j.isNull("endedAt")) j.getLong("endedAt") else null,
        residualUris = j.getJSONArray("residualUris").strings(), errorCode = j.nullable("errorCode"), error = j.nullable("error"),
    )

    private fun JSONObject.nullable(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
