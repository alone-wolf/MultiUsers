package top.writerpass.multiusers.system
import top.writerpass.multiusers.domain.CopyPrecheck
import top.writerpass.multiusers.domain.DataDirs
import top.writerpass.multiusers.domain.DeviceUser

object Parsers {
    fun users(
        text: String,
        current: Int,
    ): List<DeviceUser> {
        val parsed =
            text
                .lineSequence()
                .filter { "UserInfo{" in it }
                .map { line ->
                    // `}` 必须转义：Android 的 java.util.regex 由 ICU4C 实现，裸 `}` 会抛 "Syntax error in regexp pattern"，
                    // 而桌面 JVM 会放行，所以 :system:testDebugUnitTest 无法发现。设备端回归见 ParsersDeviceTest。
                    val m = Regex("UserInfo\\{(\\d+):(.*):([0-9a-fA-F]+)\\}(.*)").find(line) ?: error("不支持的用户列表格式")
                    DeviceUser(
                        m.groupValues[1].toInt(),
                        m.groupValues[2],
                        m.groupValues[3].toInt(16),
                        "running" in m.groupValues[4],
                        m.groupValues[1].toInt() == current,
                    )
                }.toList()
        check(parsed.isNotEmpty()) { "系统没有返回可识别的用户列表" }
        return parsed
    }

    fun packages(text: String): Map<String, Int> =
        text.lineSequence().filter { it.startsWith("package:") }.associate {
            // 兼容 `pm list packages -U` 与 `pm list packages -f -U`（后者为 package:<apk路径>=<包名> uid:<uid>）
            val m = Regex("package:(?:.*=)?([A-Za-z0-9_.]+) uid:(\\d+)").matchEntire(it.trim()) ?: error("不支持的包列表格式: $it")
            m.groupValues[1] to m.groupValues[2].toInt()
        }

    /** 从 `pm list packages -f -U` 的输出解析包名 → APK 路径（用于 PackageManager 看不到的包，如只装在其它用户）。*/
    fun apkPaths(text: String): Map<String, String> =
        text
            .lineSequence()
            .filter { it.startsWith("package:") && " uid:" in it }
            .mapNotNull { line ->
                val body = line.trim().removePrefix("package:")
                val uid = body.lastIndexOf(" uid:")
                if (uid < 0) return@mapNotNull null
                val spec = body.substring(0, uid)
                val eq = spec.lastIndexOf('=')
                if (eq <= 0) return@mapNotNull null
                val path = spec.substring(0, eq)
                val name = spec.substring(eq + 1)
                if (name.isBlank() || !path.endsWith(".apk")) null else name to path
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, paths) -> paths.firstOrNull { it.endsWith("base.apk") } ?: paths.first() }

    /** 解析 KEY=VALUE 形式的预检输出（SRC_STATE/DST_STATE/CE_SRC/DE_SRC/CE_DST/DE_DST）。*/
    fun precheck(
        text: String,
        src: Int,
        dst: Int,
    ): CopyPrecheck {
        val f =
            text
                .lineSequence()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <=
                        0
                    ) {
                        null
                    } else {
                        line.substring(0, i).trim() to line.substring(i + 1).trim()
                    }
                }.toMap()
        return CopyPrecheck(
            src,
            f["SRC_STATE"].orEmpty(),
            DataDirs(f["CE_SRC"] == "YES", f["DE_SRC"] == "YES"),
            dst,
            f["DST_STATE"].orEmpty(),
            DataDirs(f["CE_DST"] == "YES", f["DE_DST"] == "YES"),
        )
    }

    fun names(text: String) =
        text
            .lineSequence()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()
}
