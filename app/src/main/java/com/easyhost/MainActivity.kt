package com.easyhost

import android.content.Intent
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile

private val Bg = Color(0xFF101114)
private val Panel = Color(0xFF1A1C21)
private val Muted = Color(0xFF9297A2)
private val Accent = Color(0xFF9BE37A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createServerNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4102)
        }
        setContent { EasyHostApp() }
    }

    private fun createServerNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                SERVER_CHANNEL_ID,
                "Состояние EasyHost",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает, когда локальный веб-сервер EasyHost запущен"
                setShowBadge(false)
            }
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}

private const val SERVER_CHANNEL_ID = "easyhost_server_status"
private const val SERVER_NOTIFICATION_ID = 4101

private fun showServerNotification(context: android.content.Context, port: String, projectName: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
    ) return

    val notification = NotificationCompat.Builder(context, SERVER_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Изи Хост: сервер активен")
        .setContentText("$projectName · http://127.0.0.1:$port")
        .setStyle(NotificationCompat.BigTextStyle().bigText(
            "Проект «$projectName» раздаётся на порту $port.\nАдрес: http://127.0.0.1:$port"
        ))
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    try {
        NotificationManagerCompat.from(context).notify(SERVER_NOTIFICATION_ID, notification)
    } catch (_: SecurityException) {
        // Notifications may be disabled by the user or denied by Android.
    }
}

private fun hideServerNotification(context: android.content.Context) {
    NotificationManagerCompat.from(context).cancel(SERVER_NOTIFICATION_ID)
}

data class Site(val name: String, val uri: Uri, val hasIndex: Boolean, val hasPhp: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyHostApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("easyhost_settings", android.content.Context.MODE_PRIVATE) }
    var rootUri by remember { mutableStateOf(prefs.getString("root_uri", null)?.let(Uri::parse)) }
    var sites by remember { mutableStateOf(listOf<Site>()) }
    var selected by remember { mutableStateOf<Site?>(null) }
    var port by remember { mutableStateOf(prefs.getString("port", "8080") ?: "8080") }
    var server by remember { mutableStateOf<StaticSiteServer?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Выберите папку Host") }
    var showSettings by remember { mutableStateOf(false) }

    fun scan(uri: Uri) {
        val root = DocumentFile.fromTreeUri(context, uri)
        sites = root?.listFiles()?.filter { it.isDirectory }?.map { dir ->
            val children = dir.listFiles()
            Site(
                name = dir.name ?: "Без названия",
                uri = dir.uri, // было uri (root) — это был баг
                hasIndex = children.any { it.name.equals("index.html", true) },
                hasPhp = children.any { it.name.equals("index.php", true) }
            )
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val saved = prefs.getString("selected_project", null)
        if (selected == null && saved != null) selected = sites.firstOrNull { it.name == saved }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            rootUri = uri
            prefs.edit().putString("root_uri", uri.toString()).apply()
            scan(uri)
            status = "Папка подключена"
        }
    }

    LaunchedEffect(rootUri) { rootUri?.let { scan(it) } }
    LaunchedEffect(selected) { prefs.edit().putString("selected_project", selected?.name).apply() }
    LaunchedEffect(port) { prefs.edit().putString("port", port).apply() }

    fun lanIp(): String? = try {
        val wifi = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
        val ip = wifi.connectionInfo.ipAddress
        if (ip == 0) null else Formatter.formatIpAddress(ip)
    } catch (_: Exception) { null }

    fun copyAddress(value: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EasyHost URL", value))
        android.widget.Toast.makeText(context, "Ссылка скопирована", android.widget.Toast.LENGTH_SHORT).show()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Accent,
        onBackground = Color.White, onSurface = Color.White,
        onSurfaceVariant = Muted
    )) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Изи Хост", fontSize = 29.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            "LOCAL DEVELOPMENT",
                            color = Muted, fontSize = 10.sp, letterSpacing = 2.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Настройки", tint = Muted)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(if (running) Accent else Muted, RoundedCornerShape(50)))
                            Spacer(Modifier.width(9.dp))
                            Text(
                                if (running) "СЕРВЕР РАБОТАЕТ" else "СЕРВЕР ОСТАНОВЛЕН",
                                color = if (running) Accent else Muted,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (running) "http://127.0.0.1:$port" else "Готов к запуску",
                            fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            status,
                            color = Muted, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val site = selected
                                    val p = port.toIntOrNull()
                                    if (site == null) status = "Сначала выберите проект"
                                    else if (p == null || p !in 1024..65535) status = "Порт должен быть 1024–65535"
                                    else {
                                        try {
                                            server?.stop()
                                            server = StaticSiteServer(context, site.uri, site.name, p).also { it.start() }
                                            running = true
                                            status = "Раздаётся ${site.name}"
                                            showServerNotification(context, port, site.name)
                                        } catch (e: Exception) {
                                            running = false
                                            hideServerNotification(context)
                                            status = e.message ?: "Ошибка запуска"
                                        }
                                    }
                                },
                                enabled = !running,
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                                modifier = Modifier.weight(1f).height(52.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Запуск", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    server?.stop(); server = null; running = false
                                    status = "Сервер остановлен"; hideServerNotification(context)
                                },
                                enabled = running,
                                modifier = Modifier.weight(1f).height(52.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Стоп", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port")))
                                    } catch (_: Exception) {
                                        status = "Не удалось открыть браузер"
                                    }
                                },
                                enabled = running,
                                modifier = Modifier.weight(1f).height(52.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Rounded.Language, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Открыть", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    val ip = lanIp()
                                    if (ip != null) copyAddress("http://$ip:$port")
                                    else status = "Не удалось определить IP Wi-Fi"
                                },
                                enabled = running,
                                modifier = Modifier.weight(1f).height(52.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("LAN", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (running) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Для других устройств: " +
                                    (lanIp()?.let { "http://$it:$port" } ?: "IP Wi-Fi недоступен"),
                                color = Muted, fontSize = 11.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "МОИ ПРОЕКТЫ", color = Muted, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                        modifier = Modifier.weight(1f), maxLines = 1
                    )
                    TextButton(onClick = { rootUri?.let { scan(it) } }) {
                        Text("Обновить", color = Accent, maxLines = 1)
                    }
                }
                if (rootUri == null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Folder, null, tint = Accent, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Подключите папку с сайтами", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Выберите Documents/Host или создайте её",
                                color = Muted, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { folderPicker.launch(null) }) {
                                Text("Выбрать папку", maxLines = 1)
                            }
                        }
                    }
                } else if (sites.isEmpty()) {
                    Text(
                        "Пока нет проектов. Создайте папку сайта внутри Host.",
                        color = Muted, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(sites) { site ->
                            val active = selected?.name == site.name
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { selected = site },
                                colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFF252B25) else Panel),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Folder, null, tint = if (active) Accent else Muted)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            site.name, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            buildString {
                                                if (site.hasIndex) append("HTML")
                                                if (site.hasIndex && site.hasPhp) append("  ·  ")
                                                if (site.hasPhp) append("PHP")
                                                if (!site.hasIndex && !site.hasPhp) append("Нет index.html / index.php")
                                            },
                                            color = Muted, fontSize = 11.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (active) Text(
                                        "ВЫБРАН", color = Accent, fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Language, null, tint = Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Локальный хостинг · HTML / CSS / JS",
                        color = Muted, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = Panel,
                title = { Text("Настройки") },
                text = {
                    Column {
                        Text("Порт сервера", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Диапазон: 1024–65535", color = Muted, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) { Text("Готово") }
                }
            )
        }
    }
}
