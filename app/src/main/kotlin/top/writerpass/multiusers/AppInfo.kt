package top.writerpass.multiusers
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import top.writerpass.multiusers.domain.DeviceApp

/** 应用列表行：Root 侧的真实包信息 + PackageManager 解析出的显示名与图标。 */
data class AppRow(
    val app: DeviceApp,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * 显示名/图标补全。列表本身来自 root（pm list packages），这里只补展示信息。
 *
 * 逐用户查询接口（createContextAsUser / getInstalledApplicationsAsUser）都是 @hide/@SystemApi，
 * 需要 INTERACT_ACROSS_USERS，普通应用拿不到；所以用本应用用户的 PackageManager 配合
 * MATCH_UNINSTALLED_PACKAGES（清单已声明 QUERY_ALL_PACKAGES）尽量覆盖"只装在其它用户/已卸载保留数据"的包。
 * 解析不到就退化为包名 + 占位图标，绝不让整个列表失败。
 */
object AppInfo {
    private const val ICON_PX = 96
    private const val FLAGS =
        PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE

    fun enrich(
        context: Context,
        apps: List<DeviceApp>,
    ): List<AppRow> {
        val pm = context.packageManager
        val infos = LinkedHashMap<String, ApplicationInfo>()
        runCatching { pm.getInstalledApplications(FLAGS) }.getOrNull()?.forEach { infos[it.packageName] = it }
        runCatching { pm.getInstalledApplications(0) }.getOrNull()?.forEach { infos[it.packageName] = it }
        return apps.map { app ->
            // PM 看不到的包（例如只装在其它用户）：用 root 给的 APK 路径解析归档信息（公开 API；/data/app 下的 APK 可读）。
            val info = infos[app.packageName] ?: archiveInfo(pm, app.apkPath)
            val label =
                info
                    ?.let {
                        runCatching {
                            pm
                                .getApplicationLabel(
                                    it,
                                ).toString()
                        }.getOrNull()
                    }?.takeIf { it.isNotBlank() } ?: app.packageName
            val icon = info?.let { runCatching { it.loadIcon(pm).toIconBitmap(ICON_PX) }.getOrNull() }
            AppRow(app, label, icon)
        }
    }

    private fun archiveInfo(
        pm: PackageManager,
        path: String,
    ): ApplicationInfo? =
        path.takeIf { it.isNotBlank() }?.let { runCatching { pm.getPackageArchiveInfo(it, 0)?.applicationInfo }.getOrNull() }

    private fun Drawable.toIconBitmap(size: Int): ImageBitmap {
        val w = intrinsicWidth.takeIf { it > 0 } ?: size
        val h = intrinsicHeight.takeIf { it > 0 } ?: size
        val scale = minOf(size.toFloat() / w, size.toFloat() / h)
        val dw = (w * scale).toInt().coerceAtLeast(1)
        val dh = (h * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val left = (size - dw) / 2
        val top = (size - dh) / 2
        setBounds(left, top, left + dw, top + dh)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}
