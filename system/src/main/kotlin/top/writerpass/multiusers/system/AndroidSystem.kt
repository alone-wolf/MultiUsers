package top.writerpass.multiusers.system
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import top.writerpass.multiusers.domain.AppAction
import top.writerpass.multiusers.domain.Compatibility
import top.writerpass.multiusers.domain.CopyPrecheck
import top.writerpass.multiusers.domain.DeviceApp
import top.writerpass.multiusers.domain.DeviceUser
import top.writerpass.multiusers.domain.Inputs
import top.writerpass.multiusers.domain.UserAction
import top.writerpass.multiusers.domain.UserPolicy
import top.writerpass.multiusers.root.RootExecutor
import java.io.File

class AndroidSystem(
    private val context: Context,
    private val root: RootExecutor,
) {
    val ownUser get() = Process.myUid() / 100000

    private fun q(s: String) = Inputs.quote(s)

    private suspend fun cmd(
        label: String,
        command: String,
    ) = root.execute(label, command).checked().also {
        check(!Regex("(?im)^(error:|failure|exception|securityexception)").containsMatchIn(it)) { it }
    }

    private suspend fun mutation(
        label: String,
        command: String,
    ) = cmd(label, RootLock.preamble("/data/local/multiusers") + command)

    suspend fun compatibility(): Compatibility {
        val result =
            root.execute(
                "Root与能力检测",
                "set -e; id -u; getenforce; command -v pm; command -v am; command -v tar; command -v sha256sum; command -v restorecon; command -v flock",
            )
        val granted =
            result.code == 0 && result.output
                .lineSequence()
                .firstOrNull()
                ?.trim() == "0"
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        return Compatibility(
            granted,
            Build.VERSION.SDK_INT,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            result.output
                .lineSequence()
                .drop(1)
                .firstOrNull()
                .orEmpty(),
            result.output + "\n本用户解锁=$unlocked；ownUser=$ownUser\nRoot命名空间=${root.namespaceNote ?: "未探测"}\n兼容性以实际操作结果为准",
        )
    }

    suspend fun users(): List<DeviceUser> {
        val current = cmd("当前用户", "am get-current-user").trim().toInt()
        return Parsers.users(cmd("用户列表", "pm list users"), current)
    }

    suspend fun createUser(
        name: String,
        parent: Int?,
    ) {
        val args = if (parent == null) "" else "--profileOf ${Inputs.user(parent)} --managed"
        val out = mutation("创建用户", "pm create-user $args ${q(Inputs.name(name))}")
        check("Success" in out) { out }
        users()
    }

    suspend fun userAction(
        id: Int,
        action: UserAction,
    ) {
        val user = users().single { it.id == Inputs.user(id) }
        UserPolicy.check(action, user, ownUser)
        val command =
            when (action) {
                UserAction.START -> "am start-user -w $id"
                UserAction.STOP -> "am stop-user -w $id"
                UserAction.SWITCH -> "am switch-user $id"
                UserAction.DELETE -> "pm remove-user --wait $id"
            }
        mutation("用户 $id $action", command)
        val after = users()
        check(
            when (action) {
                UserAction.DELETE -> {
                    after.none { it.id == id }
                }

                UserAction.START -> {
                    after.any { it.id == id && it.running }
                }

                UserAction.STOP -> {
                    after.none {
                        it.id ==

                            id &&
                            it.running
                    }
                }
                ;UserAction.SWITCH,

                -> {
                    after.any { it.id == id && it.current }
                }
            },
        ) { "操作未达到预期状态，请刷新" }
    }

    /** 迁移前预检：两侧用户的运行/解锁状态 + 该应用CE/DE数据目录是否已创建。一次 root 调用，避免白跑一个任务。*/
    suspend fun copyPrecheck(
        src: Int,
        dst: Int,
        pkg: String,
    ): CopyPrecheck {
        val s = Inputs.user(src)
        val d = Inputs.user(dst)
        val name = Inputs.pkg(pkg)
        val out =
            cmd(
                "迁移预检 user$s→$d",
                "echo SRC_STATE=$(am get-started-user-state $s 2>/dev/null | tr -d '\r\n'); " +
                    "echo DST_STATE=$(am get-started-user-state $d 2>/dev/null | tr -d '\r\n'); " +
                    dirProbe("SRC", s, name) +
                    dirProbe("DST", d, name),
            )
        return Parsers.precheck(out, s, d)
    }

    private fun dirProbe(
        tag: String,
        id: Int,
        pkg: String,
    ) =
        "test -d /data/user/$id/$pkg && test ! -L /data/user/$id/$pkg && echo CE_$tag=YES || echo CE_$tag=NO; test -d /data/user_de/$id/$pkg && test ! -L /data/user_de/$id/$pkg && echo DE_$tag=YES || echo DE_$tag=NO"

    suspend fun apps(user: Int): List<DeviceApp> {
        val id = Inputs.user(user)
        val listing = cmd("用户$id 应用", "pm list packages -f -U --user $id")
        val all = Parsers.packages(listing)
        val paths = Parsers.apkPaths(listing)
        val disabled = Parsers.names(cmd("禁用列表", "pm list packages -d --user $id"))
        val system = Parsers.names(cmd("系统包列表", "pm list packages -s --user $id"))
        return all
            .map { (name, uid) ->
                DeviceApp(name, uid, name !in disabled, name in system, paths[name].orEmpty())
            }.sortedBy { it.packageName }
    }

    suspend fun appAction(
        user: Int,
        pkg: String,
        action: AppAction,
    ) {
        val id = Inputs.user(user)
        val name = q(Inputs.pkg(pkg))
        require(pkg != context.packageName) { "不能修改管理器自身" }
        val command =
            when (action) {
                AppAction.ENABLE -> "pm enable --user $id $name"
                AppAction.DISABLE -> "pm disable-user --user $id $name"
                AppAction.UNINSTALL -> "pm uninstall --user $id $name"
                AppAction.INSTALL_EXISTING -> "pm install-existing --user $id $name"
            }
        mutation("应用 $id $pkg $action", command)
        val after = apps(id).find { it.packageName == pkg }
        check(
            when (action) {
                AppAction.ENABLE -> {
                    after?.enabled == true
                }

                AppAction.DISABLE -> {
                    after?.enabled == false
                }

                AppAction.UNINSTALL -> {
                    after == null
                }

                AppAction.INSTALL_EXISTING -> {
                    after !=

                        null
                }
            },
        ) { "操作未达到预期状态，请刷新" }
    }

    suspend fun installApk(
        user: Int,
        file: File,
    ) {
        val id = Inputs.user(user)
        require(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + "/") && file.isFile)
        val dst = "/data/local/tmp/multiusers-${java.util.UUID.randomUUID()}.apk"
        mutation(
            "安装APK到用户$id",
            "set -e; umask 077; trap ${q(
                "rm -f ${q(dst)}",
            )} EXIT; cp ${q(file.path)} ${q(dst)}; chmod 644 ${q(dst)}; pm install --user $id ${q(dst)}",
        )
    }
}
