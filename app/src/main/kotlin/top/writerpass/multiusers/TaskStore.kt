package top.writerpass.multiusers
import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import top.writerpass.multiusers.domain.MigrationKind
import top.writerpass.multiusers.domain.MigrationRequest
import top.writerpass.multiusers.root.AuditSink
import java.io.File

data class TaskRecord(
    val request: MigrationRequest,
    val status: String,
    val message: String,
    val updated: Long = System.currentTimeMillis(),
)

class TaskStore(
    context: Context,
) {
    private val file = AtomicFile(File(context.filesDir, "tasks.json"))
    var loadError: String? = null
        private set
    private val mutable =
        MutableStateFlow(
            runCatching { read() }.getOrElse {
                loadError = "任务索引损坏，已禁止新任务以保留恢复证据：${it.message}"
                emptyList()
            },
        )
    val tasks = mutable.asStateFlow()

    private fun read(): List<TaskRecord> {
        if (!file.baseFile.exists()) return emptyList()
        val data = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        return (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            TaskRecord(
                MigrationRequest(
                    o.getString("id"),
                    MigrationKind.valueOf(o.getString("kind")),
                    o.getString("pkg"),
                    o.getInt("source"),
                    o.getInt("target"),
                    o.optString("backup"),
                ),
                o.getString("status"),
                o.getString("message"),
                o.getLong("updated"),
            )
        }
    }

    @Synchronized fun save(record: TaskRecord) {
        check(loadError == null) { loadError.orEmpty() }
        val next =
            (
                mutable.value.filterNot {
                    it.request.id == record.request.id
                } + record.copy(updated = System.currentTimeMillis())
            ).sortedByDescending { it.updated }
        val array = JSONArray()
        next.forEach { t ->
            array.put(
                JSONObject()
                    .put(
                        "id",
                        t.request.id,
                    ).put(
                        "kind",
                        t.request.kind.name,
                    ).put(
                        "pkg",
                        t.request.packageName,
                    ).put(
                        "source",
                        t.request.source,
                    ).put(
                        "target",
                        t.request.target,
                    ).put("backup", t.request.backupId)
                    .put("status", t.status)
                    .put("message", t.message.takeLast(16000))
                    .put("updated", t.updated),
            )
        }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray())
            file.finishWrite(stream)
        } catch (
            e: Exception,
        ) {
            file.failWrite(stream)
            throw e
        }
        mutable.value =
            next
    }

    fun get(id: String) = mutable.value.single { it.request.id == id }

    @Synchronized fun update(
        id: String,
        change: (TaskRecord) -> TaskRecord,
    ) {
        save(change(get(id)))
    }
}

class FileAudit(
    context: Context,
) : AuditSink {
    private val file = File(context.filesDir, "operations.log")
    private val mutable = MutableStateFlow(if (file.exists()) file.readText().takeLast(64000) else "")
    val logs = mutable.asStateFlow()

    @Synchronized override fun write(message: String) {
        val line = "${java.time.Instant.now()} $message\n"
        if (file.length() >
            2_000_000
        ) {
            file.copyTo(File(file.parentFile, "operations.previous.log"), overwrite = true)
            file.writeText("")
        }
        file.appendText(line)
        mutable.value =
            (mutable.value + line).takeLast(64000)
    }
}
