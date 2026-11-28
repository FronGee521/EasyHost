package com.easyhost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class ServerForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var server: StaticSiteServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val tree = intent.getStringExtra(EXTRA_TREE)?.let(Uri::parse)
                val project = intent.getStringExtra(EXTRA_PROJECT)
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                if (tree == null || project.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification(project, port))
                executor.execute {
                    try {
                        stopServer()
                        server = StaticSiteServer(this, tree, project, port).also {
                            // 60 c на чтение — чтобы большие загрузки не обрывались
                            it.start(60_000, false)
                        }
                        setRunning(true)
                        broadcast(ACTION_STARTED, null)
                    } catch (e: Exception) {
                        setRunning(false)
                        broadcast(ACTION_FAILED, e.message ?: "Не удалось запустить сервер")
                        stopServer()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(project: String, port: Int): Notification {
        val stopPending = PendingIntent.getService(
            this, 71,
            Intent(this, ServerForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this, 72, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Изи Хост · сервер работает")
            .setContentText("$project · http://127.0.0.1:$port")
            .setContentIntent(openPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Состояние Изи Хост", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает состояние локального веб-сервера"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setRunning(value: Boolean) {
        getSharedPreferences("easyhost_settings", MODE_PRIVATE)
            .edit().putBoolean("server_running", value).apply()
    }

    private fun broadcast(action: String, error: String?) {
        sendBroadcast(Intent(action).setPackage(packageName).putExtra(EXTRA_ERROR, error))
    }

    private fun stopServer() {
        try { server?.stop() } catch (_: Exception) { }
        server = null
        setRunning(false)
    }

    override fun onDestroy() {
        stopServer()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.easyhost.action.START"
        const val ACTION_STOP = "com.easyhost.action.STOP"
        const val ACTION_STARTED = "com.easyhost.action.STARTED"
        const val ACTION_FAILED = "com.easyhost.action.FAILED"
        const val EXTRA_TREE = "tree_uri"
        const val EXTRA_PROJECT = "project_name"
        const val EXTRA_PORT = "port"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "easyhost_server_status"
        private const val NOTIFICATION_ID = 4101
    }
}
