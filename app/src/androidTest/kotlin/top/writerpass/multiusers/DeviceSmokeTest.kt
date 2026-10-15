package top.writerpass.multiusers
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.writerpass.multiusers.domain.MigrationKind
import top.writerpass.multiusers.domain.MigrationRequest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultiUsersApp

    @Test fun apiAndTaskPersistence() {
        assertTrue(Build.VERSION.SDK_INT >= 36)
        val context = app.createDeviceProtectedStorageContext()
        val isolated =
            object : android.content.ContextWrapper(context) {
                override fun getFilesDir() = java.io.File(context.cacheDir, "task-store-test").apply { mkdirs() }
            }
        val r = MigrationRequest(UUID.randomUUID().toString(), MigrationKind.BACKUP, "test.fixture", 0, 0)
        TaskStore(isolated).save(TaskRecord(r, "QUEUED", "test"))
        assertEquals(r, TaskStore(isolated).get(r.id).request)
    }

    @Test fun rootReadOnlySmoke() =
        runBlocking {
            assumeTrue(
                "Opt in with rootTests=true",
                InstrumentationRegistry.getArguments().getString("rootTests") == "true",
            )
            ;assertTrue(
                app.graph.system
                    .compatibility()
                    .root,
            )
            val users = app.graph.system.users()
            assertTrue(users.any { it.current })
            assertTrue(
                users.any {
                    it.id ==
                        0
                },
            )
            assertTrue(
                app.graph.system
                    .apps(app.graph.system.ownUser)
                    .any { it.packageName == app.packageName },
            )
        }

    @Test fun fixtureCopyVerifyAndRecovery() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Disposable root device only", args.getString("migrationTests") == "true")
            val target = requireNotNull(args.getString("targetUser")).toInt()
            require(target >= 10)
            val source = app.graph.system.ownUser
            require(source != target)
            val pkg = "top.writerpass.multiusers.fixture"
            val engine = app.graph.engine
            val before =
                app.graph.root
                    .execute("fixture before", "sha256sum /data/user/$target/$pkg/files/seed.txt")
                    .checked()
            val copy = MigrationRequest(UUID.randomUUID().toString(), MigrationKind.COPY, pkg, source, target)
            assertTrue(engine.execute(copy).contains("RESULT=SUCCEEDED"))
            app.graph.root
                .execute(
                    "compare fixture",
                    "cmp /data/user/$source/$pkg/files/seed.txt /data/user/$target/$pkg/files/seed.txt && cmp /data/user_de/$source/$pkg/files/seed.txt /data/user_de/$target/$pkg/files/seed.txt",
                ).checked()
            assertTrue(
                engine
                    .execute(
                        MigrationRequest(UUID.randomUUID().toString(), MigrationKind.VERIFY, pkg, source, target, copy.id),
                    ).contains("RESULT=SUCCEEDED"),
            )
            assertTrue(
                engine
                    .execute(
                        MigrationRequest(UUID.randomUUID().toString(), MigrationKind.RECOVER, pkg, source, target, copy.id),
                    ).contains("RESULT=SUCCEEDED"),
            )
            assertEquals(
                before,
                app.graph.root
                    .execute("fixture recovered", "sha256sum /data/user/$target/$pkg/files/seed.txt")
                    .checked(),
            )
        }
}
