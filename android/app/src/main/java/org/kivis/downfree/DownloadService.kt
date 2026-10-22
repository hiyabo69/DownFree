package org.kivis.downfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DownloadService : Service() {
    inner class LocalBinder : Binder() { fun service(): DownloadService = this@DownloadService }
    private val binder = LocalBinder()
    private lateinit var repository: DownloadRepository
    internal lateinit var engine: DownloadEngine
        private set
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var webServer: LocalWebServer

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository(this)
        engine = DownloadEngine(repository, ::updateNotification)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Servicio de descargas en ejecución", 0, false))
        webServer = LocalWebServer(engine)
        runCatching { webServer.start() }
        serviceScope.launch { engine.restore() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_URL)?.let(engine::enqueue)
            ACTION_PAUSE -> engine.pause(intent.getStringExtra(EXTRA_FILE).orEmpty())
            ACTION_RESUME -> engine.resume(intent.getStringExtra(EXTRA_FILE).orEmpty())
            ACTION_PAUSE_ALL -> engine.pauseAll()
            ACTION_RESUME_ALL -> engine.resumeAll()
            ACTION_SET_LIMIT -> engine.setMaxActive(intent.getIntExtra(EXTRA_LIMIT, 1))
            ACTION_CANCEL -> serviceScope.launch { engine.cancel(intent.getStringExtra(EXTRA_FILE).orEmpty()) }
            ACTION_CLEAR -> serviceScope.launch { engine.clearCompleted() }
        }
        return START_STICKY
    }

    fun startDownload(raw: String) { engine.enqueue(raw); acquireWakeLock() }
    fun setDownloadLimit(value: Int) = engine.setMaxActive(value)
    fun updateSelectedPath(path: String?) {
        engine.setSelectedPath(path)
        getSharedPreferences("downfree", MODE_PRIVATE).edit().putString("selected_path", path).apply()
        val theme = getSharedPreferences("downfree", MODE_PRIVATE).getString("theme_mode", "Dark") ?: "Dark"
        serviceScope.launch { repository.saveConfig(path, theme) }
    }

    fun getSelectedFileUri(name: String): android.net.Uri? = runCatching {
        val selected = getSharedPreferences("downfree", MODE_PRIVATE).getString("selected_path", null)
        repository.getDownloadUri(name, selected)
    }.getOrNull()
    fun pauseDownload(name: String) = engine.pause(name)
    fun resumeDownload(name: String) = engine.resume(name)
    fun pauseAllDownloads() = engine.pauseAll()
    fun resumeAllDownloads() = engine.resumeAll()
    fun cancelDownload(name: String) { serviceScope.launch { engine.cancel(name) } }
    fun clearCompletedDownloads() { serviceScope.launch { engine.clearCompleted() } }
    fun loadConfiguration(block: (String, String) -> Unit) {
        serviceScope.launch {
            val c = repository.loadConfig()
            val path = c.optString("selected_path")
            val theme = c.optString("theme_mode", "Dark")
            getSharedPreferences("downfree", MODE_PRIVATE).edit().putString("selected_path", path).putString("theme_mode", theme).apply()
            block(path, theme)
        }
    }
    fun saveConfiguration(path: String?, theme: String) {
        getSharedPreferences("downfree", MODE_PRIVATE).edit().putString("selected_path", path).putString("theme_mode", theme).apply()
        serviceScope.launch { repository.saveConfig(path, theme) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:DownloadService").apply { setReferenceCounted(false); acquire(10 * 60 * 60 * 1000L) }
    }

    private fun updateNotification(item: DownloadItem) {
        val hasWork = engine.items.value.any { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
        if (hasWork) acquireWakeLock() else wakeLock?.let { if (it.isHeld) it.release() }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val text = when (item.state) {
            DownloadState.COMPLETED -> "${item.fileName} descargado con éxito"
            DownloadState.ERROR -> item.error ?: "Error de descarga"
            DownloadState.PAUSED -> "Descarga pausada"
            else -> "${item.progress}% completado · ${formatBytes(item.speedBytesPerSecond.toLong())}/s"
        }
        manager.notify(item.fileName.hashCode().and(0x7fff) + 200, notification(text, item.progress, item.state !in setOf(DownloadState.DOWNLOADING, DownloadState.QUEUED)))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Servicio de Descarga", NotificationManager.IMPORTANCE_HIGH).apply { description = "Progreso y resultados de descargas" },
            )
        }
    }

    private fun notification(text: String, progress: Int, done: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(if (done) "Down Free" else "Gestor de Descargas")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(!done)
            .setProgress(100, progress, false).setPriority(NotificationCompat.PRIORITY_HIGH).build()
    }

    override fun onDestroy() {
        runCatching { webServer.stop() }
        engine.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "download_service"
        const val NOTIFICATION_ID = 101
        const val EXTRA_URL = "url"
        const val EXTRA_FILE = "file"
        const val EXTRA_LIMIT = "limit"
        const val ACTION_START = "org.kivis.downfree.START"
        const val ACTION_PAUSE = "org.kivis.downfree.PAUSE"
        const val ACTION_RESUME = "org.kivis.downfree.RESUME"
        const val ACTION_PAUSE_ALL = "org.kivis.downfree.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "org.kivis.downfree.RESUME_ALL"
        const val ACTION_SET_LIMIT = "org.kivis.downfree.SET_LIMIT"
        const val ACTION_CANCEL = "org.kivis.downfree.CANCEL"
        const val ACTION_CLEAR = "org.kivis.downfree.CLEAR"
    }
}
