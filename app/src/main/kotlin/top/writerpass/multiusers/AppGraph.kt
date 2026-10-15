package top.writerpass.multiusers
import android.app.Application
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import top.writerpass.multiusers.migration.MigrationEngine
import top.writerpass.multiusers.root.SuExecutor
import top.writerpass.multiusers.system.AndroidSystem

class AppGraph(
    context: Context,
) {
    val audit = FileAudit(context)
    val root = SuExecutor(audit)
    val system = AndroidSystem(context, root)
    val store = TaskStore(context)
    val engine = MigrationEngine(context, root)
    val operations = Mutex()
}

class MultiUsersApp : Application() {
    val graph by lazy { AppGraph(this) }
}
