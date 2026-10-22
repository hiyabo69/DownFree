package org.kivis.downfree

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow

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
        startAndBindService()
        setContent {
            DownFreeApp(
                service = downloadService,
                onOpenFile = ::openDownloadedFile,
                onShowMessage = ::toast,
                onRequestBatteryExemption = ::requestBatteryExemption,
            )
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, DownloadService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        if (!manager.isIgnoringBatteryOptimizations(packageName)) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        }
    }

    private fun openDownloadedFile(name: String) {
        val root = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val selected = getSharedPreferences("downfree", Context.MODE_PRIVATE).getString("selected_path", null)
        val storageRoot = selected?.takeIf { it.isNotBlank() && !it.startsWith("content://") }?.let(::File) ?: root
        val fileName = File(name).name
        val selectedFile = if (selected?.startsWith("content://") == true) null else File(File(storageRoot, "Down_Free"), fileName)
        val publicFile = File(File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Down_Free"), fileName)
        val stagingFallback = File(File(root, "Down_Free/Complete"), fileName)
        val file = selectedFile?.takeIf(File::exists) ?: publicFile.takeIf(File::exists) ?: stagingFallback
        val uri = downloadService?.getSelectedFileUri(name) ?: if (file.exists()) FileProvider.getUriForFile(this, "$packageName.files", file) else null
        if (uri == null) { toast("El archivo no existe."); return }
        val extension = File(name).extension
        val mime = if (extension.equals("apk", true)) "application/vnd.android.package-archive"
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: contentResolver.getType(uri) ?: "*/*"
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

private data class DownFreePalette(
    val brand: Color,
    val background: Color,
    val card: Color,
    val input: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val button: Color,
    val buttonBorder: Color,
    val danger: Color,
    val success: Color,
)

private fun publicDownloadTreeUri(): Uri = DocumentsContract.buildTreeDocumentUri(
    "com.android.externalstorage.documents",
    "primary:${Environment.DIRECTORY_DOWNLOADS}/Down_Free",
)

private fun continueStorageSelection(
    context: Context,
    selectedPath: String,
    storagePermissionLauncher: ActivityResultLauncher<Array<String>>,
    launchTreePicker: () -> Unit,
) {
    if (selectedPath.startsWith("content://")) return
    if (Build.VERSION.SDK_INT < 29) {
        val missing = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            storagePermissionLauncher.launch(missing.toTypedArray())
            return
        }
    }
    launchTreePicker()
}

private fun palette(dark: Boolean) = if (dark) DownFreePalette(
    brand = Color(0xFF00BFCB), background = Color(0xFF0A0A0D), card = Color(0xFF141419),
    input = Color(0xFF1A1A1F), inputBorder = Color(0xFF33333D), primaryText = Color.White,
    secondaryText = Color(0xFF80808C), button = Color(0xFF1F1F24), buttonBorder = Color(0xFF404047),
    danger = Color(0xFFE65C66), success = Color(0xFF33CC66),
) else DownFreePalette(
    brand = Color(0xFF0099A6), background = Color(0xFFF2F2F7), card = Color.White,
    input = Color(0xFFE6E6ED), inputBorder = Color(0xFFCCCCD9), primaryText = Color(0xFF1A1A1A),
    secondaryText = Color(0xFF666673), button = Color(0xFFE6E6ED), buttonBorder = Color(0xFFBFBFCC),
    danger = Color(0xFFE65C66), success = Color(0xFF33A65B),
)

@Composable
private fun DownFreeApp(
    service: DownloadService?,
    onOpenFile: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(true) }
    var maxActive by remember { mutableIntStateOf(1) }
    var selectedPath by remember { mutableStateOf("") }
    var infoOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var configLoaded by remember { mutableStateOf(false) }
    var startupAccessPromptStarted by remember { mutableStateOf(false) }
    var batteryPromptAttempted by remember { mutableStateOf(false) }
    val emptyItems = remember { MutableStateFlow<List<DownloadItem>>(emptyList()) }
    val itemFlow = service?.engine?.items ?: emptyItems
    val items = itemFlow.collectAsState().value
    val clipboard: ClipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val colors = palette(dark)
    fun requestBatteryExemptionOnce() {
        if (!batteryPromptAttempted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            batteryPromptAttempted = true
            onRequestBatteryExemption()
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { service?.contentResolverPlaceholder(uri) }
            selectedPath = uri.toString()
            service?.updateSelectedPath(uri.toString())
            onShowMessage("Carpeta seleccionada")
        } else {
            selectedPath = ""
            service?.updateSelectedPath(null)
        }
        requestBatteryExemptionOnce()
    }
    fun launchPreparedDownloadPicker() {
        val currentService = service
        if (currentService == null) {
            folderPicker.launch(publicDownloadTreeUri())
            return
        }
        currentService.preparePublicDownloadsFolder { prepared ->
            if (prepared) {
                folderPicker.launch(publicDownloadTreeUri())
            } else {
                onShowMessage("No se pudo crear Download/Down_Free; continuaré usando MediaStore")
                selectedPath = ""
                currentService.updateSelectedPath(null)
                requestBatteryExemptionOnce()
            }
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        launchPreparedDownloadPicker()
    }
    fun startStorageAndBatteryFlow() {
        if (selectedPath.startsWith("content://")) {
            requestBatteryExemptionOnce()
        } else {
            continueStorageSelection(context, selectedPath, storagePermissionLauncher, ::launchPreparedDownloadPicker)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startStorageAndBatteryFlow()
    }

    LaunchedEffect(service) {
        service?.loadConfiguration { path, theme ->
            selectedPath = path
            dark = theme != "Light"
            configLoaded = true
        }
    }
    LaunchedEffect(configLoaded) {
        if (!configLoaded || startupAccessPromptStarted) return@LaunchedEffect
        startupAccessPromptStarted = true
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startStorageAndBatteryFlow()
        }
    }
    val active = items.count { it.state == DownloadState.DOWNLOADING }
    val queued = items.count { it.state == DownloadState.QUEUED }
    val complete = items.count { it.state == DownloadState.COMPLETED }
    val totalSpeed = items.sumOf { it.speedBytesPerSecond.toLong() }
    val totalRemaining = items.filter { it.state == DownloadState.DOWNLOADING }.sumOf { (it.totalBytes - it.downloadedBytes).coerceAtLeast(0) }
    val eta = if (totalSpeed > 1024) formatDuration(totalRemaining / totalSpeed) else "--"

    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = colors.brand, onPrimary = Color(0xFF00383C), background = colors.background,
            onBackground = colors.primaryText, surface = colors.card, onSurface = colors.primaryText,
            surfaceVariant = colors.input, onSurfaceVariant = colors.secondaryText, outline = colors.buttonBorder,
            error = colors.danger,
        ) else lightColorScheme(
            primary = colors.brand, onPrimary = Color.White, background = colors.background,
            onBackground = colors.primaryText, surface = colors.card, onSurface = colors.primaryText,
            surfaceVariant = colors.input, onSurfaceVariant = colors.secondaryText, outline = colors.buttonBorder,
            error = colors.danger,
        ),
    ) {
        Scaffold(containerColor = colors.background) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(
                    colors = colors, dark = dark, active = active, queued = queued, complete = complete,
                    onInfo = { infoOpen = true },
                    onToggleTheme = { dark = !dark; service?.saveConfiguration(selectedPath, if (dark) "Dark" else "Light") },
                )
                DownloadInput(
                    value = url, onValueChange = { url = it }, colors = colors,
                    onPaste = { clipboard.getText()?.text?.let { url = it } },
                    onDownload = {
                        if (url.isBlank()) onShowMessage("Pega los metadatos de descarga")
                        else runCatching { DownloadRequest.parse(url) }
                            .onSuccess { service?.startDownload(url); url = "" }
                            .onFailure { onShowMessage(it.message ?: "Metadatos de descarga no válidos") }
                    },
                )
                GlobalStats(totalSpeed, eta, colors)
                Row(
                    Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillButton("Pausar", colors) { service?.pauseAllDownloads() }
                    PillButton("Reanudar", colors) { service?.resumeAllDownloads() }
                    PillButton("Limpiar", colors, foreground = colors.danger) { service?.clearCompletedDownloads() }
                    PillButton("Ajustes", colors) { settingsOpen = true }
                    PillButton("Máx activas: $maxActive", colors) {
                        maxActive = maxActive % 3 + 1
                        service?.setDownloadLimit(maxActive)
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.fileName }) { item ->
                        DownloadCard(
                            item = item, colors = colors,
                            onPause = { service?.pauseDownload(item.fileName) },
                            onResume = { service?.resumeDownload(item.fileName) },
                            onRetry = { service?.startDownload(item.urlData) },
                            onCancel = { service?.cancelDownload(item.fileName) },
                            onOpen = { onOpenFile(item.fileName) },
                        )
                    }
                }
                Text(
                    text = "Ruta: ${displayPath(selectedPath)}",
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    color = colors.secondaryText, fontSize = 11.sp, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (infoOpen) {
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            containerColor = colors.card,
            title = { Text("Down Free v7.8.8", color = colors.primaryText, fontWeight = FontWeight.SemiBold) },
            text = { Text("Gestor de descargas para uso sin consumo de megas. Perteneciente al servicio Uploader Free. Para más información, visita nuestro canal de Telegram @UploaderFreeChannel.", color = colors.secondaryText) },
            confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("CERRAR", color = colors.brand) } },
        )
    }

    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            containerColor = colors.card,
            title = { Text("Ajustes", color = colors.primaryText, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Carpeta de descarga", color = colors.secondaryText)
                    TextButton(onClick = { settingsOpen = false; launchPreparedDownloadPicker() }) {
                        HugeIcon(R.drawable.ic_huge_folder, "Seleccionar carpeta", colors.brand, 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Seleccionar carpeta", color = colors.brand)
                    }
                    TextButton(onClick = { settingsOpen = false; onRequestBatteryExemption() }) {
                        Text("Optimización de batería", color = colors.brand)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("CERRAR", color = colors.brand) } },
        )
    }
}

@Composable
private fun Header(
    colors: DownFreePalette,
    dark: Boolean,
    active: Int,
    queued: Int,
    complete: Int,
    onInfo: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(70.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Descargas", modifier = Modifier.weight(1f), color = colors.primaryText,
                fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
            IconAction(R.drawable.ic_huge_info, "Información", colors.secondaryText, onInfo)
            IconAction(if (dark) R.drawable.ic_huge_sun else R.drawable.ic_huge_moon, "Cambiar tema", colors.secondaryText, onToggleTheme)
        }
        val transition = rememberInfiniteTransition(label = "connection-indicator")
        val alpha by transition.animateFloat(
            initialValue = 1f, targetValue = .6f,
            animationSpec = infiniteRepeatable(tween(1600), repeatMode = RepeatMode.Reverse),
            label = "connection-indicator-alpha",
        )
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Color(0xFF20E45A).copy(alpha = alpha), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("$active activas  -  $queued cola  -  $complete completas", color = colors.secondaryText, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun DownloadInput(
    value: String,
    onValueChange: (String) -> Unit,
    colors: DownFreePalette,
    onPaste: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                .background(colors.input).border(1.dp, colors.inputBorder, RoundedCornerShape(12.dp))
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text("Pega la URL aqui...", color = colors.secondaryText, fontSize = 13.sp, maxLines = 1)
                BasicTextField(
                    value = value, onValueChange = onValueChange, singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "URL o datos de descarga" },
                    textStyle = TextStyle(color = colors.primaryText, fontSize = 13.sp),
                    cursorBrush = SolidColor(colors.brand),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
            }
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPaste)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HugeIcon(R.drawable.ic_huge_paste, "Pegar del portapapeles", colors.brand, 15.dp)
                Spacer(Modifier.width(4.dp))
                Text("PEGAR", color = colors.brand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(colors.input).clickable(onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) { HugeIcon(R.drawable.ic_huge_download, "Descargar", colors.brand, 25.dp) }
    }
}

@Composable
private fun GlobalStats(totalSpeed: Long, eta: String, colors: DownFreePalette) {
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Velocidad global", color = colors.secondaryText, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text(formatSpeed(totalSpeed, global = true), color = colors.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("ETA global", color = colors.secondaryText, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        Text(eta, color = colors.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun PillButton(text: String, colors: DownFreePalette, foreground: Color = colors.brand, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(colors.button)
            .border(1.dp, colors.buttonBorder, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, color = foreground, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    colors: DownFreePalette,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.buttonBorder.copy(alpha = .45f)),
        modifier = Modifier.fillMaxWidth().height(110.dp)
            .clickable(enabled = item.state == DownloadState.COMPLETED, onClick = onOpen),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.fileName, Modifier.weight(1f), color = colors.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (item.state) { DownloadState.ERROR -> "Error"; DownloadState.COMPLETED -> "100%"; else -> "${item.progress}%" },
                    color = if (item.state == DownloadState.ERROR) colors.danger else colors.primaryText,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.error ?: when (item.state) {
                        DownloadState.QUEUED -> "Pendiente..."
                        DownloadState.DOWNLOADING -> "Descargando..."
                        DownloadState.PAUSED -> "Pausado"
                        DownloadState.COMPLETED -> "Descarga Finalizada"
                        DownloadState.ERROR -> "Error en descarga"
                        DownloadState.CANCELLED -> "Cancelada"
                    },
                    Modifier.weight(1f), color = colors.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(if (item.state == DownloadState.COMPLETED) "Completa" else "ETA ${formatDuration(item.etaSeconds)}", color = colors.secondaryText, fontSize = 11.sp)
            }
            LinearProgressIndicator(
                progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = colors.brand, trackColor = colors.button,
            )
            Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val totalText = if (item.totalBytes > 0) formatBytes(item.totalBytes) else "0 MB"
                val currentText = if (item.totalBytes > 0) formatBytes(item.downloadedBytes) else "0 MB"
                Text("$currentText / $totalText", Modifier.weight(1f), color = colors.secondaryText, fontSize = 11.sp, maxLines = 1)
                Text(formatSpeed(item.speedBytesPerSecond.toLong(), global = false), color = colors.secondaryText, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (item.state) {
                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> CardAction("Pausar", R.drawable.ic_huge_pause, colors.secondaryText, onPause)
                    DownloadState.PAUSED -> CardAction("Reanudar", R.drawable.ic_huge_play, colors.brand, onResume)
                    DownloadState.ERROR -> CardAction("Reintentar", R.drawable.ic_huge_retry, colors.success, onRetry)
                    else -> Unit
                }
                if (item.state != DownloadState.COMPLETED) CardAction("Eliminar", R.drawable.ic_huge_delete, colors.danger, onCancel)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CardAction(label: String, icon: Int, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HugeIcon(icon, label, color, 14.dp)
        Text(label, color = color, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun IconAction(icon: Int, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        HugeIcon(icon, label, tint, 22.dp)
    }
}

@Composable
private fun HugeIcon(icon: Int, label: String, tint: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Image(
        painter = painterResource(icon), contentDescription = label,
        modifier = Modifier.size(size), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
    )
}

private fun displayPath(path: String): String = when {
    path.isBlank() -> "Download/Down_Free"
    path.startsWith("content://") -> {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(path)) }.getOrDefault("")
        if (documentId == "primary:Download" || documentId == "primary:Download/Down_Free") "Download/Down_Free" else "Carpeta personalizada"
    }
    else -> path
}

private fun formatSpeed(bytesPerSecond: Long, global: Boolean): String = when {
    global && bytesPerSecond < 1024 -> "0.00 MB/s"
    bytesPerSecond < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
    else -> "%.2f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
}

private fun DownloadService.contentResolverPlaceholder(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
}
