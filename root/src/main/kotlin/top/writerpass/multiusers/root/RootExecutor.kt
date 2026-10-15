package top.writerpass.multiusers.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun interface AuditSink {
    fun write(message: String)
}

data class ShellResult(
    val code: Int,
    val output: String,
) {
    fun checked(): String {
        check(code == 0) { "Root命令失败 ($code): ${output.takeLast(2000)}" }
        return output
    }
}

interface RootExecutor {
    suspend fun execute(
        label: String,
        script: String,
        timeoutSeconds: Long = 60,
    ): ShellResult

    /** su 挂载命名空间探测结论；null = 尚未探测。展示在"检测Root"卡片并写入审计日志，用于排障。*/
    val namespaceNote: String?
}

/** 探测脚本：列出所有用户，报告 /data/user/<id> 在当前 su 的挂载命名空间里是否可见。*/
internal const val NAMESPACE_PROBE =
    "for u in \$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do " +
        "s=\$(am get-started-user-state \"\$u\" 2>/dev/null | tr -d '\\r\\n'); " +
        "[ -e \"/data/user/\$u\" ] && echo \"OK \$u(\$s)\" || echo \"NO \$u(\$s)\"; done"

internal fun namespaceNote(
    output: String,
    mode: String,
): String {
    val facts =
        output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "未探测到用户目录" }
    return "挂载命名空间=" + mode + "；存储可见性：" + facts
}

/** Integration modules construct trusted scripts, never raw UI input. */
class SuExecutor(
    private val audit: AuditSink,
) : RootExecutor {
    // Android 上非当前用户的 /data/user/<id> 可能是"用户启动后才出现的挂载"，而 app 的挂载命名空间在
    // zygote fork 时就已固定：su 继承该命名空间时会看不到其它用户的 CE 存储（真机表现为 /data/user/10
    // 整目录不可见）。所以先探测一次：能用全局命名空间（Magisk: --mount-master）就用，否则退回默认 su，
    // 并把结论写进审计日志与"检测Root"卡片，避免"检测结果与实际不符"。
    private val probeLock = Mutex()

    @Volatile private var suArgs: List<String>? = null

    @Volatile override var namespaceNote: String? = null
        private set

    override suspend fun execute(
        label: String,
        script: String,
        timeoutSeconds: Long,
    ): ShellResult =
        withContext(Dispatchers.IO) {
            audit.write("BEGIN $label")
            runProcess(label, prefix() + script, timeoutSeconds)
        }

    private suspend fun prefix(): List<String> = suArgs ?: probeLock.withLock { suArgs ?: probeNamespace().also { suArgs = it } }

    private suspend fun probeNamespace(): List<String> {
        val master = runProcess("su全局命名空间探测", listOf("su", "--mount-master", "-c", NAMESPACE_PROBE), 30)
        if (master.code == 0) {
            namespaceNote = namespaceNote(master.output, "--mount-master（全局命名空间）")
            audit.write("ROOT $namespaceNote")
            return listOf("su", "--mount-master", "-c")
        }
        val plain = runProcess("su命名空间探测", listOf("su", "-c", NAMESPACE_PROBE), 30)
        namespaceNote =
            if (plain.code == 0) {
                namespaceNote(
                    plain.output,
                    "默认命名空间",
                )
            } else {
                "su 命名空间探测失败（rc=${plain.code}）：${plain.output.takeLast(200)}"
            }
        audit.write("ROOT $namespaceNote")
        return listOf("su", "-c")
    }

    private fun runProcess(
        label: String,
        command: List<String>,
        timeoutSeconds: Long,
    ): ShellResult {
        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (
                e: Exception,
            ) {
                audit.write("ERROR $label ${e.javaClass.simpleName}")
                throw IllegalStateException("无法启动su，请安装并授权Magisk / KernelSU / APatch", e)
            }
        val output = CompletableFuture<String>()
        Thread({
            try {
                output.complete(drain(process.inputStream))
            } catch (
                e: Exception,
            ) {
                output.completeExceptionally(e)
            }
        }, "multiusers-su-output").apply {
            isDaemon =
                true
            ;start()
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                audit.write("TIMEOUT $label；结果未知，需刷新/恢复")
                error("操作超时，结果未知。请读取日志，不要重复执行")
            }
            val result = ShellResult(process.exitValue(), output.get(5, TimeUnit.SECONDS))
            audit.write("END $label exit=${result.code}\n${result.output.takeLast(4000)}")
            return result
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun drain(input: InputStream): String =
        input.bufferedReader().use { r ->
            val text = StringBuilder()
            val buf = CharArray(4096)
            var truncated = false
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                text.append(buf, 0, n)
                if (text.length > 262144) {
                    text.delete(0, text.length - 262144)
                    truncated = true
                }
            }
            check(!truncated) { "Root输出超过安全上限，已拒绝使用不完整结果" }
            text.toString()
        }
}
