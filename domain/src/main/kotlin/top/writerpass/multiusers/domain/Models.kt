package top.writerpass.multiusers.domain

object Inputs {
    fun user(v: Int) = v.also { require(it in 0..21473) { "无效用户ID" } }

    fun pkg(v: String) =
        v.also {
            require(it.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(it)) { "无效包名" }
        }

    fun task(v: String) = v.also { require(Regex("[a-f0-9-]{36}").matches(it)) { "无效任务ID" } }

    fun name(v: String) =
        v.trim().also {
            require(it.isNotEmpty() && it.length <= 64 && it.none { c -> c.isISOControl() }) { "名称须为1–64个非控制字符" }
        }

    fun quote(v: String): String {
        require('\u0000' !in v)
        return "'" + v.replace("'", "'\\''") + "'"
    }
}

data class DeviceUser(
    val id: Int,
    val name: String,
    val flags: Int,
    val running: Boolean,
    val current: Boolean = false,
) {
    val managed get() = flags and 0x20 != 0
    val profile get() = flags and 0x1000 != 0 || managed
}

data class DeviceApp(
    val packageName: String,
    val uid: Int,
    val enabled: Boolean,
    val system: Boolean,
    val apkPath: String = "",
)

data class DataDirs(
    val ce: Boolean,
    val de: Boolean,
) {
    val ready get() = ce && de
}

/** 复制/恢复前的预检：两侧用户的运行解锁状态与数据目录状态。*/
data class CopyPrecheck(
    val src: Int,
    val srcState: String,
    val srcDirs: DataDirs,
    val dst: Int,
    val dstState: String,
    val dstDirs: DataDirs,
)

data class Compatibility(
    val root: Boolean,
    val sdk: Int,
    val manufacturer: String,
    val selinux: String,
    val details: String,
)

enum class UserAction { START, STOP, SWITCH, DELETE }

enum class AppAction { ENABLE, DISABLE, UNINSTALL, INSTALL_EXISTING }

enum class MigrationKind { COPY, BACKUP, RESTORE, RECOVER, VERIFY }

data class MigrationRequest(
    val id: String,
    val kind: MigrationKind,
    val packageName: String,
    val source: Int,
    val target: Int,
    val backupId: String = "",
) {
    fun validate() {
        Inputs.task(id)
        Inputs.pkg(packageName)
        Inputs.user(source)
        Inputs.user(target)
        if (kind == MigrationKind.COPY) require(source != target) { "源和目标必须不同" }
        if (kind in listOf(MigrationKind.RESTORE, MigrationKind.RECOVER, MigrationKind.VERIFY)) Inputs.task(backupId)
        require(packageName != "top.writerpass.multiusers") { "不能迁移管理器自身" }
    }
}

object UserPolicy {
    fun check(
        action: UserAction,
        user: DeviceUser,
        ownId: Int,
    ) {
        if (action in listOf(UserAction.DELETE, UserAction.STOP)) {
            require(user.id != 0 && user.id != ownId && !user.current) {
                "禁止删除或停止系统、当前或管理器所在用户"
            }
        }
        if (action == UserAction.SWITCH) require(!user.profile) { "工作资料不是可切换的完整用户，请选择启动" }
    }
}
