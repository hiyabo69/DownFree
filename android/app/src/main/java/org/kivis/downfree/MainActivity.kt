package org.kivis.downfree

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.collectLatest
import java.io.File

class MainActivity : ComponentActivity() {
    private var downloadService by mutableStateOf<DownloadService?>(null)
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            downloadService = (binder as? DownloadService.LocalBinder)?.service()
        }
        override fun onServiceDisconnected(name: ComponentName?) { downloadService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        requestLegacyCompatibleStorageAccess()
        startAndBindService()
        setContent {
            DownFreeApp(
                service = downloadService,
                onOpenFile = ::openDownloadedFile,
                onShowMessage = ::toast,
                onRequestAllFiles = ::requestAllFilesAccess,
                onRequestBatteryExemption = ::requestBatteryExemption,
            )
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, DownloadService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun requestLegacyCompatibleStorageAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            val missing = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 11)
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun requestBatteryExemption() {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        if (!manager.isIgnoringBatteryOptimizations(packageName)) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        }
    }

    private fun openDownloadedFile(name: String) {
        val root = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val selected = getSharedPreferences("downfree", Context.MODE_PRIVATE).getString("selected_path", null)
        val storageRoot = selected?.takeIf { it.isNotBlank() && !it.startsWith("content://") }?.let(::File) ?: root
        val selectedFile = File(File(storageRoot, "Down_Free/Downloads"), File(name).name)
        val fallbackFile = File(File(root, "Down_Free/Downloads"), File(name).name)
        val file = selectedFile.takeIf(File::exists) ?: fallbackFile
        val uri = downloadService?.getSelectedFileUri(name) ?: if (file.exists()) FileProvider.getUriForFile(this, "$packageName.files", file) else null
        if (uri == null) { toast("El archivo no existe."); return }
        val extension = File(name).extension
        val mime = if (extension.equals("apk", true)) "application/vnd.android.package-archive" else contentResolver.getType(uri) ?: "*/*"
        if (extension.equals("apk", true) && Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            toast("Autoriza la instalación de apps y vuelve a abrir el archivo.")
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Error al abrir o no hay aplicación disponible.") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        if (bound) unbindService(serviceConnection)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownFreeApp(service: DownloadService?, onOpenFile: (String) -> Unit, onShowMessage: (String) -> Unit, onRequestAllFiles: () -> Unit, onRequestBatteryExemption: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(true) }
    var maxActive by remember { mutableIntStateOf(1) }
    var selectedPath by remember { mutableStateOf("") }
    var infoOpen by remember { mutableStateOf(false) }
    val items = service?.engine?.items?.collectAsState()?.value.orEmpty()
    val clipboard: ClipboardManager = LocalClipboardManager.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { service?.contentResolverPlaceholder(uri) }
            selectedPath = uri.toString()
            service?.updateSelectedPath(uri.toString())
            onShowMessage("Carpeta seleccionada")
        }
    }

    LaunchedEffect(service) { service?.loadConfiguration { path, theme -> selectedPath = path; dark = theme != "Light" } }
    val active = items.count { it.state == DownloadState.DOWNLOADING }
    val queued = items.count { it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED }
    val complete = items.count { it.state == DownloadState.COMPLETED }
    val totalSpeed = items.sumOf { it.speedBytesPerSecond.toLong() }
    val totalRemaining = items.filter { it.state == DownloadState.DOWNLOADING }.sumOf { (it.totalBytes - it.downloadedBytes).coerceAtLeast(0) }
    val eta = if (totalSpeed > 1024) formatDuration(totalRemaining / totalSpeed) else "--"

    MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme(primary = Color(0xFF00C1CC), background = Color(0xFF0A0A0D), surface = Color(0xFF15151A)) else androidx.compose.material3.lightColorScheme(primary = Color(0xFF008F99))) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Descargas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("●  $active activas  -  $queued cola  -  $complete completas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { infoOpen = true }) { Text("ⓘ") }
                    Switch(checked = dark, onCheckedChange = { dark = it; service?.saveConfiguration(selectedPath, if (it) "Dark" else "Light") })
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Velocidad global", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("${formatBytes(totalSpeed)}/s · ETA $eta", fontWeight = FontWeight.SemiBold)
                }

                OutlinedTextField(
                    value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pega la URL aqui...") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    trailingIcon = { TextButton(onClick = { clipboard.getText()?.text?.let { url = it } }) { Text("PEGAR") } },
                )
                Button(onClick = {
                    if (url.isBlank()) onShowMessage("Pega los metadatos de descarga")
                    else runCatching { DownloadRequest.parse(url) }
                        .onSuccess { service?.startDownload(url); url = "" }
                        .onFailure { onShowMessage(it.message ?: "Metadatos de descarga no válidos") }
                }, modifier = Modifier.fillMaxWidth()) { Text("↓  Descargar") }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { service?.pauseAllDownloads() }, modifier = Modifier.weight(1f)) { Text("Pausar") }
                    OutlinedButton(onClick = { service?.resumeAllDownloads() }, modifier = Modifier.weight(1f)) { Text("Reanudar") }
                    OutlinedButton(onClick = { service?.clearCompletedDownloads() }, modifier = Modifier.weight(1f)) { Text("Limpiar") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) { Text("Carpeta") }
                    OutlinedButton(onClick = { maxActive = maxActive % 3 + 1; service?.setDownloadLimit(maxActive) }) { Text("Máx activas: $maxActive") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onRequestAllFiles, modifier = Modifier.weight(1f)) { Text("Acceso a archivos") }
                    TextButton(onClick = onRequestBatteryExemption, modifier = Modifier.weight(1f)) { Text("Optimización batería") }
                }
                if (selectedPath.isNotBlank()) Text("Ruta: $selectedPath", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.fileName }) { item ->
                        DownloadCard(item, onPause = { service?.pauseDownload(item.fileName) }, onResume = { service?.resumeDownload(item.fileName) }, onRetry = { service?.startDownload(item.urlData) }, onCancel = { service?.cancelDownload(item.fileName) }, onOpen = { onOpenFile(item.fileName) })
                    }
                }
            }
        }
    }
    if (infoOpen) AlertDialog(onDismissRequest = { infoOpen = false }, title = { Text("Down Free v7.8.8") }, text = { Text("Gestor de descargas para uso sin consumo de megas. Perteneciente al servicio Uploader Free. Para más información, visita nuestro canal de Telegram @UploaderFreeChannel.") }, confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("CERRAR") } })
}

@Composable
private fun DownloadCard(item: DownloadItem, onPause: () -> Unit, onResume: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = item.state == DownloadState.COMPLETED, onClick = onOpen)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.fileName, Modifier.weight(1f), maxLines = 1, fontWeight = FontWeight.Bold)
                Text(if (item.state == DownloadState.COMPLETED) "100%" else if (item.state == DownloadState.ERROR) "Error" else "${item.progress}%")
            }
            Text(item.error ?: when (item.state) { DownloadState.QUEUED -> "Pendiente..."; DownloadState.DOWNLOADING -> "Descargando..."; DownloadState.PAUSED -> "Pausado"; DownloadState.COMPLETED -> "Descarga Finalizada"; DownloadState.ERROR -> "Error en descarga"; DownloadState.CANCELLED -> "Cancelada" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PAUSED) LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}", style = MaterialTheme.typography.labelSmall)
                Text("${formatBytes(item.speedBytesPerSecond.toLong())}/s · ETA ${formatDuration(item.etaSeconds)}", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.QUEUED) TextButton(onClick = onPause) { Text("Pausar") }
                if (item.state == DownloadState.PAUSED) TextButton(onClick = onResume) { Text("Reanudar") }
                if (item.state == DownloadState.ERROR) TextButton(onClick = onRetry) { Text("Reintentar") }
                if (item.state != DownloadState.COMPLETED) TextButton(onClick = onCancel) { Text("Eliminar") }
                if (item.state == DownloadState.COMPLETED) TextButton(onClick = onOpen) { Text("Abrir") }
            }
        }
    }
}

private fun DownloadService.contentResolverPlaceholder(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
}
