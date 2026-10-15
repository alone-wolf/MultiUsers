package top.writerpass.multiusers
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.writerpass.multiusers.domain.AppAction
import top.writerpass.multiusers.domain.Compatibility
import top.writerpass.multiusers.domain.CopyPrecheck
import top.writerpass.multiusers.domain.DeviceUser
import top.writerpass.multiusers.domain.MigrationKind
import top.writerpass.multiusers.domain.MigrationRequest
import top.writerpass.multiusers.domain.UserAction
import java.io.File
import java.util.UUID

data class UiState(
    val busy: Boolean = false,
    val compatibility: Compatibility? = null,
    val users: List<DeviceUser> = emptyList(),
    val apps: List<AppRow> = emptyList(),
    val selected: Int? = null,
    val message: String = "点击检测并授权Root",
    val precheck: CopyPrecheck? = null,
)

class MainViewModel(
    app: Application,
) : AndroidViewModel(app) {
    val graph = (app as MultiUsersApp).graph
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    val tasks = graph.store.tasks
    val logs = graph.audit.logs

    private fun operation(
        exclusive: Boolean = true,
        block: suspend () -> Unit,
    ) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = "执行中…") }
        viewModelScope.launch {
            try {
                if (exclusive) {
                    graph.operations.withLock {
                        withContext(
                            Dispatchers.IO,
                        ) { block() }
                    }
                } else {
                    withContext(Dispatchers.IO) { block() }
                }
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (
                e: Exception,
            ) {
                mutable.update { it.copy(message = e.message ?: "操作失败") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun detect() =
        operation {
            val c = graph.system.compatibility()
            mutable.update { it.copy(compatibility = c) }
            check(c.root) { "Root未获授权或缺少必要系统工具。\n${c.details}" }
            check(graph.store.loadError == null) { graph.store.loadError.orEmpty() }
            refreshNow()
        }

    private suspend fun refreshNow() {
        val users = graph.system.users()
        val selected =
            mutable.value.selected?.takeIf { id ->
                users.any { it.id == id }
            } ?: users.firstOrNull { it.current }?.id ?: users.first().id
        val apps = graph.system.apps(selected)
        mutable.update {
            it.copy(
                users = users,
                selected = selected,
                apps = AppInfo.enrich(getApplication(), apps),
                message = "已读取${users.size}个用户 / ${apps.size}个应用",
            )
        }
    }

    fun refresh() = operation { refreshNow() }

    fun select(id: Int) =
        operation {
            val apps = graph.system.apps(id)
            mutable.update { it.copy(selected = id, apps = AppInfo.enrich(getApplication(), apps), message = "用户$id") }
        }

    private fun checkIdle() {
        check(graph.store.loadError == null) { graph.store.loadError.orEmpty() }
        check(
            tasks.value.none {
                it.status in
                    listOf("QUEUED", "RUNNING")
            },
        ) { "有数据任务正在执行" }
    }

    fun userAction(
        user: Int,
        action: UserAction,
    ) = operation {
        checkIdle()
        graph.system.userAction(user, action)
        refreshNow()
    }

    fun create(
        name: String,
        parent: Int?,
    ) = operation {
        checkIdle()
        graph.system.createUser(name, parent)
        refreshNow()
    }

    /** 复制/恢复前预检：两侧用户的运行解锁状态 + 数据目录状态（一次 root 调用）。*/
    fun checkCopy(
        pkg: String,
        src: Int?,
        dst: Int?,
    ) = operation(exclusive = false) {
        if (pkg.isBlank() || src == null || dst == null) {
            mutable.update { it.copy(precheck = null) }
            return@operation
        }
        mutable.update { it.copy(precheck = graph.system.copyPrecheck(src, dst, pkg)) }
    }

    fun appAction(
        user: Int,
        pkg: String,
        action: AppAction,
    ) = operation {
        checkIdle()
        graph.system.appAction(user, pkg, action)
        refreshNow()
    }

    fun install(
        user: Int,
        uri: Uri,
    ) = operation {
        checkIdle()
        val file = File(getApplication<Application>().cacheDir, "install-${UUID.randomUUID()}.apk")
        try {
            getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取APK" }
                file.outputStream().use { input.copyTo(it) }
            }
            graph.system.installApk(user, file)
            refreshNow()
        } finally {
            file.delete()
        }
    }

    fun enqueue(
        kind: MigrationKind,
        pkg: String,
        source: Int,
        target: Int,
        backup: String = "",
    ) = operation {
        checkIdle()
        val r = MigrationRequest(UUID.randomUUID().toString(), kind, pkg, source, target, backup)
        r.validate()
        graph.store.save(TaskRecord(r, "QUEUED", "等待执行"))
        val work =
            OneTimeWorkRequestBuilder<MigrationWorker>()
                .setInputData(
                    workDataOf("task" to r.id),
                ).addTag("multiusers-migration")
                .addTag("task:${r.id}")
                .build()
        WorkManager
            .getInstance(
                getApplication(),
            ).enqueueUniqueWork("multiusers-migration", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
            .result
            .get()
        mutable.update { it.copy(message = "任务已持久化：${r.id}") }
    }

    fun inspect(record: TaskRecord) =
        operation(exclusive = false) {
            val report = graph.engine.inspect(record.request.id)
            val work = WorkManager.getInstance(getApplication()).getWorkInfosByTag("task:${record.request.id}").get()
            graph.store.update(record.request.id) { latest ->
                val status =
                    when {
                        "RESULT=SUCCEEDED" in report -> {
                            "SUCCEEDED"
                        }

                        "RESULT=RECOVERED"

                            in report -> {
                            "RECOVERED"
                        }

                        "RESULT=FAILED"

                            in
                            report -> {
                            "FAILED"
                        }

                        work.isEmpty() || work.all { it.state.isFinished } -> {
                            if (latest.status

                                in
                                listOf("RUNNING", "QUEUED")
                            ) {
                                "INTERRUPTED"
                            } else {
                                latest.status
                            }
                        }
                        ;else

                        -> {
                            latest.status
                        }
                    }
                ;latest.copy(status = status, message = report)
            }

            mutable.update { it.copy(message = "已更新任务日志") }
        }
}
