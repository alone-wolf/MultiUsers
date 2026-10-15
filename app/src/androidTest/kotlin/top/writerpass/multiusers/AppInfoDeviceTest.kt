package top.writerpass.multiusers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.writerpass.multiusers.domain.DeviceApp

/**
 * 设备端回归：应用列表的显示名与图标必须能从 PackageManager 解析出来。
 * Settings 这类系统包默认对本应用不可见，只有 QUERY_ALL_PACKAGES 生效时才能拿到名称和图标，
 * 所以这里的断言同时守护"清单漏声明包可见性"这类回归；解析失败必须是退化为包名 + 无图标，
 * 而不是抛异常让整个列表挂掉。
 */
@RunWith(AndroidJUnit4::class)
class AppInfoDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun resolvesLabelAndIconForSystemApp() {
        val row = AppInfo.enrich(context, listOf(DeviceApp("com.android.settings", 1000, true, true))).single()
        assertNotEquals("未解析出应用名（检查 QUERY_ALL_PACKAGES 是否生效）", "com.android.settings", row.label)
        assertNotNull("未解析出图标", row.icon)
    }

    /** 可选环境准备：把某个包只装到另一个用户，再带 -e otherUserPackage=<pkg> -e otherUserApkPath=<apk> 运行，
     *  验证"PackageManager 看不到该包时用 APK 归档解析名称/图标"这条回退链在设备上确实生效。 */
    @Test fun resolvesNameAndIconFromApkArchive() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("需要 -e otherUserApkPath=<apk path>", args.getString("otherUserApkPath") != null)
        val pkg = args.getString("otherUserPackage").orEmpty()
        val path = args.getString("otherUserApkPath")!!
        val row = AppInfo.enrich(context, listOf(DeviceApp(pkg, 10777, true, false, path))).single()
        assertNotEquals("APK 归档未解析出名称", "$pkg", row.label)
        assertNotNull("APK 归档未解析出图标", row.icon)
    }

    @Test fun unknownPackageFallsBackInsteadOfFailing() {
        val row = AppInfo.enrich(context, listOf(DeviceApp("top.writerpass.does.not.exist", 10777, false, false))).single()
        assertEquals("top.writerpass.does.not.exist", row.label)
        assertNull(row.icon)
    }
}
