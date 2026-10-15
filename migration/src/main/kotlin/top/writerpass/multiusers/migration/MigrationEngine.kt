package top.writerpass.multiusers.migration
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.writerpass.multiusers.domain.Inputs
import top.writerpass.multiusers.domain.MigrationRequest
import top.writerpass.multiusers.root.RootExecutor
import java.io.File

class MigrationEngine(
    private val context: Context,
    private val root: RootExecutor,
) {
    suspend fun execute(request: MigrationRequest): String =
        withContext(Dispatchers.IO) {
            request.validate()
            val engine = File(context.filesDir, "engine.sh")
            context.assets.open("engine.sh").use { i -> engine.outputStream().use { i.copyTo(it) } }
            val args =
                listOf(
                    request.kind.name.lowercase(),
                    request.id,
                    request.packageName,
                    request.source.toString(),
                    request.target.toString(),
                    request.backupId,
                ).joinToString(" ", transform = Inputs::quote)
            val result = root.execute("迁移 ${request.kind} ${request.id}", "/system/bin/sh ${Inputs.quote(engine.path)} $args", 1800)
            val report = inspect(request.id)
            check(result.code == 0 && report.lineSequence().any { it == "RESULT=SUCCEEDED" }) { result.output + "\n" + report }
            report
        }

    suspend fun inspect(id: String): String {
        Inputs.task(id)
        return root
            .execute(
                "任务日志$id",
                "d=${Inputs.quote(
                    "/data/local/multiusers/$id",
                )}; if [ -d \"\$d\" ]; then printf 'STATE='; cat \"\$d/state\"; printf 'RESULT='; cat \"\$d/result\" 2>/dev/null || true; tail -n 100 \"\$d/log\"; else echo '尚无Root执行记录'; fi",
            ).checked()
    }
}
