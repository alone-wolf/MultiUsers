package top.writerpass.multiusers
import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.writerpass.multiusers.domain.AppAction
import top.writerpass.multiusers.domain.DataDirs
import top.writerpass.multiusers.domain.DeviceUser
import top.writerpass.multiusers.domain.MigrationKind
import top.writerpass.multiusers.domain.UserAction
import top.writerpass.multiusers.domain.UserPolicy

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MultiUsersScreen() } }
    }
}

private data class Confirmation(
    val title: String,
    val detail: String,
    val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MultiUsersScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var managed by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var copyPackage by remember { mutableStateOf<String?>(null) }
    var copySource by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<Int?>(null) }
    var restoreRecord by remember { mutableStateOf<TaskRecord?>(null) }
    var pendingApkUser by remember { mutableStateOf<Int?>(null) }
    val active = tasks.any { it.status in listOf("QUEUED", "RUNNING") }
    val enabled = state.compatibility?.root == true && !state.busy && !active
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val apkPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val user = pendingApkUser
            if (uri != null &&
                user != null
            ) {
                confirm = Confirmation("安装APK", "安装到用户$user。仅支持单APK；拆分包请使用已有安装复制。系统验证签名。") { vm.install(user, uri) }
            }
        }
    Scaffold(topBar = {
        TopAppBar(title = {
            Text("MultiUsers")
        }, actions = { TextButton(onClick = { vm.detect() }, enabled = !state.busy && !active) { Text("检测Root") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy || active) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                state.message,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
            )
            TabRow(selectedTabIndex = tab) {
                listOf("用户", "应用", "任务", "日志").forEachIndexed {
                    i,
                    title,
                    ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            state.compatibility?.let { c ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(if (c.root) "Root已授权" else "Root未就绪", style = MaterialTheme.typography.titleMedium)
                                        Text("${c.manufacturer} · SDK ${c.sdk} · ${c.selinux}")
                                        Text(c.details, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { create = true }, enabled = enabled) { Text("创建用户 / 资料") }
                                OutlinedButton(onClick = { vm.refresh() }, enabled = enabled) { Text("刷新") }
                            }
                        }
                        items(state.users, key = { it.id }) { u ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(u.name, style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "ID ${u.id} · ${if (u.managed) {
                                            "工作资料"
                                        } else if (u.profile) {
                                            "资料"
                                        } else {
                                            "系统用户"
                                        }} · ${if (u.running) "运行中" else "已停止"}${if (u.current) " · 当前" else ""}",
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            vm.select(u.id)
                                            tab = 1
                                        }, enabled = enabled) { Text("应用") }
                                        listOf(
                                            UserAction.START to "启动",
                                            UserAction.STOP to "停止",
                                            UserAction.SWITCH to "切换",
                                            UserAction.DELETE to "删除",
                                        ).forEach { (a, label) ->
                                            val allowed = runCatching { UserPolicy.check(a, u, vm.graph.system.ownUser) }.isSuccess
                                            TextButton(enabled = enabled && allowed, onClick = {
                                                confirm =
                                                    Confirmation(
                                                        "$label ${u.name}",
                                                        if (a ==
                                                            UserAction.DELETE
                                                        ) {
                                                            "将永久删除用户${u.id}及其全部数据。请先验证备份，此操作不可撤销。"
                                                        } else {
                                                            "对用户${u.id}执行$label，运行中的应用可能中断。"
                                                        },
                                                    ) { vm.userAction(u.id, a) }
                                            }) { Text(label) }
                                        }
                                    }
                                }
                            }
                        }
                        item { Text("工作资料由系统创建；不附带企业DPC策略，也不绕过OEM用户数量限制。", style = MaterialTheme.typography.bodySmall) }
                    }
                }

                1 -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        UserPicker(state.users, state.selected, enabled) { vm.select(it) }
                        OutlinedTextField(value = search, onValueChange = {
                            search = it
                        }, label = { Text("搜索应用名 / 包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row {
                            TextButton(enabled = enabled && state.selected != null, onClick = {
                                pendingApkUser = state.selected
                                apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                            }) { Text("安装APK") }
                            TextButton(enabled = enabled, onClick = { vm.refresh() }) { Text("刷新") }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(
                                state.apps.filter {
                                    it.label.contains(search, true) || it.app.packageName.contains(search, true)
                                },
                                key = { it.app.packageName },
                            ) { row ->
                                val app = row.app
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (row.icon !=
                                                null
                                            ) {
                                                Image(row.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                                            } else {
                                                Box(
                                                    Modifier
                                                        .size(
                                                            40.dp,
                                                        ).clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                                )
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(row.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                            }
                                        }
                                        Text(
                                            "UID ${app.uid} · ${if (app.enabled) "已启用" else "已禁用"} · ${if (app.system) "系统" else "用户应用"}",
                                            Modifier.padding(top = 6.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        FlowRow {
                                            val user = state.selected ?: 0
                                            val editable = enabled && app.packageName != "top.writerpass.multiusers"
                                            TextButton(enabled = editable, onClick = {
                                                val a = if (app.enabled) AppAction.DISABLE else AppAction.ENABLE
                                                confirm =
                                                    Confirmation(
                                                        "${if (app.enabled) "禁用" else "启用"}应用",
                                                        "用户$user：${app.packageName}。系统应用变更可能影响设备功能。",
                                                    ) {
                                                        vm.appAction(user, app.packageName, a)
                                                    }
                                            }) { Text(if (app.enabled) "禁用" else "启用") }
                                            TextButton(enabled = editable, onClick = {
                                                confirm =
                                                    Confirmation(
                                                        "卸载应用",
                                                        "从用户${user}卸载${app.packageName}并删除此用户的应用数据。请先备份。",
                                                    ) { vm.appAction(user, app.packageName, AppAction.UNINSTALL) }
                                            }) { Text("卸载") }
                                            TextButton(enabled = editable && !app.system, onClick = {
                                                copyPackage = app.packageName
                                                copySource = user
                                                target =
                                                    state.users.firstOrNull { it.id != user }?.id
                                                restoreRecord = null
                                            }) { Text("复制到…") }
                                            TextButton(enabled = editable && !app.system, onClick = {
                                                confirm =
                                                    Confirmation("备份数据", migrationWarning(app.packageName, user, null)) {
                                                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                        vm.enqueue(MigrationKind.BACKUP, app.packageName, user, user)
                                                        tab =
                                                            2
                                                    }
                                            }) { Text("备份") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { Text("备份包含内部CE/DE文件；不含外部存储、账户服务或硬件密钥。失败后先读日志，确认执行结束再恢复。", style = MaterialTheme.typography.bodySmall) }
                        items(tasks, key = { it.request.id }) { t ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${t.request.kind} · ${t.status}", style = MaterialTheme.typography.titleMedium)
                                    Text(t.request.packageName)
                                    Text(
                                        "${t.request.source} → ${t.request.target} · ${t.request.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    SelectionContainer { Text(t.message.takeLast(4000), style = MaterialTheme.typography.bodySmall) }
                                    taskHint(t.message)?.let { h ->
                                        Text(h, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    FlowRow {
                                        TextButton(enabled = !state.busy, onClick = { vm.inspect(t) }) { Text("读取日志") }
                                        if (t.request.kind in listOf(MigrationKind.COPY, MigrationKind.BACKUP) && t.status == "SUCCEEDED") {
                                            TextButton(enabled = enabled, onClick = {
                                                vm.enqueue(
                                                    MigrationKind.VERIFY,
                                                    t.request.packageName,
                                                    t.request.source,
                                                    t.request.target,
                                                    t.request.id,
                                                )
                                            }) { Text("验证备份") }
                                            TextButton(enabled = enabled, onClick = {
                                                restoreRecord = t
                                                copyPackage = t.request.packageName
                                                copySource = t.request.source
                                                target =
                                                    state.selected
                                            }) { Text("恢复到…") }
                                        }
                                        if (t.status in listOf("FAILED", "INTERRUPTED") &&
                                            t.request.kind in listOf(MigrationKind.COPY, MigrationKind.RESTORE, MigrationKind.BACKUP)
                                        ) {
                                            TextButton(enabled = enabled, onClick = {
                                                confirm =
                                                    Confirmation("恢复执行前状态", "恢复原任务${t.request.id}的目标快照及原启用状态。若从未覆盖目标，仅恢复启用状态。请先查看日志。") {
                                                        vm.enqueue(
                                                            MigrationKind.RECOVER,
                                                            t.request.packageName,
                                                            t.request.source,
                                                            t.request.target,
                                                            t.request.id,
                                                        )
                                                    }
                                            }) { Text("失败恢复") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    SelectionContainer {
                        Text(
                            logs.ifEmpty {
                                "暂无操作日志"
                            },
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(copyPackage, target) { copyPackage?.let { vm.checkCopy(it, copySource, target) } }
    if (create) {
        AlertDialog(onDismissRequest = { create = false }, title = { Text("创建用户") }, text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") })
                Row {
                    Checkbox(checked = managed, onCheckedChange = { managed = it })
                    Text("工作资料（属于选中用户）", Modifier.padding(top = 12.dp))
                }
                Text("父用户：${state.selected}，OEM可拒绝不支持的类型或数量。")
            }
        }, confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val n = name
                val parent = if (managed) state.selected else null
                create = false
                confirm =
                    Confirmation("确认创建", "创建${if (managed) "工作资料，父用户$parent" else "系统用户"}：$n") { vm.create(n, parent) }
            }) { Text("下一步") }
        }, dismissButton = {
            TextButton(onClick = {
                create =
                    false
            }) { Text("取消") }
        })
    }
    if (copyPackage !=
        null
    ) {
        AlertDialog(onDismissRequest = { copyPackage = null }, title = {
            Text(
                if (restoreRecord ==
                    null
                ) {
                    "复制应用"
                } else {
                    "恢复备份"
                },
            )
        }, text = {
            Column {
                Text(copyPackage.orEmpty())
                UserPicker(state.users, target, true) { target = it }
                state.precheck?.takeIf { it.src == copySource && it.dst == target }?.let { p ->
                    val srcOk = p.srcState == "RUNNING_UNLOCKED" && p.srcDirs.ready
                    val dstOk = p.dstState == "RUNNING_UNLOCKED" && p.dstDirs.ready
                    Text(
                        "源用户${p.src}：${stateText(p.srcState)} · ${dirText(p.srcDirs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (srcOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "目标用户${p.dst}：${stateText(p.dstState)} · ${dirText(p.dstDirs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dstOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Text(migrationWarning(copyPackage.orEmpty(), copySource, target))
            }
        }, confirmButton = {
            TextButton(
                enabled =
                    target != null && (restoreRecord != null || target != copySource),
                onClick = {
                    val pkg = copyPackage!!
                    val dst = target!!
                    val src = copySource
                    val backup = restoreRecord
                    copyPackage = null
                    confirm =
                        Confirmation("确认覆盖目标数据", migrationWarning(pkg, src, dst)) {
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            vm.enqueue(
                                if (backup ==
                                    null
                                ) {
                                    MigrationKind.COPY
                                } else {
                                    MigrationKind.RESTORE
                                },
                                pkg,
                                src,
                                dst,
                                backup?.request?.id.orEmpty(),
                            )
                            tab = 2
                        }
                },
            ) { Text("复制 / 恢复数据") }
        }, dismissButton = {
            Row {
                if (restoreRecord ==
                    null
                ) {
                    TextButton(enabled = target != null && target != copySource, onClick = {
                        val pkg = copyPackage!!
                        val dst = target!!
                        copyPackage = null
                        confirm =
                            Confirmation("仅安装应用", "将已有代码注册到用户$dst，保留目标原有数据。") { vm.appAction(dst, pkg, AppAction.INSTALL_EXISTING) }
                    }) { Text("仅安装") }
                }
                TextButton(onClick = { copyPackage = null }) { Text("取消") }
            }
        })
    }
    confirm?.let { c ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(c.title) }, text = { Text(c.detail) }, confirmButton = {
            TextButton(onClick = {
                confirm =
                    null
                ;c.action()
            }) { Text("确认执行") }
        }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
}

@Composable private fun UserPicker(
    users: List<DeviceUser>,
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = {
            expanded =
                true
        }) { Text("用户：${users.find{it.id == selected}?.let{"${it.name} (${it.id})"} ?: "请选择"}") }
        ;DropdownMenu(expanded = expanded, onDismissRequest = {
            expanded =
                false
        }) {
            users.forEach { u ->
                DropdownMenuItem(text = { Text("${u.name} (${u.id})") }, onClick = {
                    expanded = false
                    onSelect(u.id)
                })
            }
        }
    }
}

private fun migrationWarning(
    pkg: String,
    source: Int,
    target: Int?,
) = "$pkg\n源用户$source${target?.let{
    " → 目标用户$it"
}.orEmpty()}。\n" +
    "要求源与目标用户都已解锁，且该应用在两个用户下都已启动过一次（CE/DE数据目录已由系统创建）。" +
    "操作会短暂禁用应用；目标原数据先备份再覆盖，源数据保留。\n" +
    "Keystore / StrongBox密钥、通行密钥及硬件绑定凭据可能无法迁移，可能需要重新登录。操作期间不要启动相关应用。"

private fun stateText(s: String) =
    when (s) {
        "RUNNING_UNLOCKED" -> "已启动并解锁"
        "RUNNING_LOCKED" -> "运行中但未解锁"
        "STOPPED" -> "未启动"
        "" -> "状态未知"
        else -> s
    }

private fun dirText(d: DataDirs) =
    when {
        d.ready -> "数据目录已就绪"
        d.ce -> "缺少DE数据目录，需在该用户下先启动一次应用"
        d.de -> "缺少CE数据目录，需在该用户下先启动一次应用"
        else -> "没有数据目录，复制/恢复会失败：需在该用户下先启动一次应用"
    }

private fun taskHint(message: String): String? =
    when {
        message.contains("data directory unavailable (source") -> "源用户下该应用还没有数据目录：请先在源用户里启动一次该应用，或改用“仅安装”只把应用注册到目标用户。"
        message.contains("data directory unavailable (target") -> "目标用户下该应用还没有数据目录：请先在该用户里安装并启动一次该应用。"
        message.contains("data directory unavailable") -> "源或目标用户下该应用还没有数据目录：请先在对应用户里启动一次该应用。"
        message.contains("must be running and unlocked") -> "用户没有启动或未解锁：先在“用户”页对相应用户点“启动”，完成解锁后重试（本次未写入目标数据）。"
        message.contains("another migration is running") -> "有另一个Root任务（迁移或管理写操作）仍持锁运行，稍后重试。"
        else -> null
    }
