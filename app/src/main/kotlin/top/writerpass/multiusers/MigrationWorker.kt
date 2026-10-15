package top.writerpass.multiusers
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

class MigrationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as MultiUsersApp).graph
        val id = inputData.getString("task") ?: return Result.failure()
        val record = graph.store.get(id)
        if (record.status !=
            "QUEUED"
        ) {
            graph.store.save(record.copy(status = "INTERRUPTED", message = "任务曾开始，已阻止自动重试。请读取Root日志并恢复。"))
            return Result.failure()
        }
        return graph.operations.withLock {
            try {
                setForeground(getForegroundInfo())
                graph.store.save(record.copy(status = "RUNNING", message = "执行中；请勿启动相关应用、重启或切换用户。"))
                val report = graph.engine.execute(record.request)
                graph.store.save(record.copy(status = "SUCCEEDED", message = report))
                Result.success()
            } catch (
                e: CancellationException,
            ) {
                graph.store.save(record.copy(status = "INTERRUPTED", message = "任务中断；Root操作可能仍在运行，先读取日志再恢复。"))
                throw e
            } catch (
                e: Exception,
            ) {
                graph.store.save(record.copy(status = "FAILED", message = e.message ?: e.javaClass.simpleName))
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext
            .getSystemService(
                NotificationManager::class.java,
            ).createNotificationChannel(NotificationChannel("migration", "数据迁移", NotificationManager.IMPORTANCE_LOW))
        val pending =
            PendingIntent.getActivity(
                applicationContext,
                0,
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(
                    applicationContext,
                    "migration",
                ).setSmallIcon(
                    android.R.drawable.stat_sys_upload,
                ).setContentTitle("MultiUsers数据任务")
                .setContentText("正在备份或恢复，请勿启动相关应用")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        return ForegroundInfo(4100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
