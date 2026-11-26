package com.easyhost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.net.Inet4Address
import java.net.NetworkInterface

private val Bg = Color(0xFF0B0F14)
private val Panel = Color(0xFF151C24)
private val PanelSoft = Color(0xFF1D2732)
private val Muted = Color(0xFF91A0AF)
private val Accent = Color(0xFFB7F27C)
private val AccentSoft = Color(0xFF263A28)
private val prefsName = "easyhost_preferences"

private const val SERVER_CHANNEL_ID = "easyhost_server_status"
private const val SERVER_NOTIFICATION_ID = 4101

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

private fun showServerNotification(context: Context, port: String, projectName: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
    ) return

    val notification = NotificationCompat.Builder(context, SERVER_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("EasyHost: сервер активен")
        .setContentText("$projectName · http://127.0.0.1:$port")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    try {
        NotificationManagerCompat.from(context).notify(SERVER_NOTIFICATION_ID, notification)
    } catch (_: SecurityException) {
        // Уведомления могут быть запрещены пользователем.
    }
}

private fun hideServerNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(SERVER_NOTIFICATION_ID)
}

private data class Site(
    val name: String,
    val documentUri: Uri,
    val rootUri: Uri,
    val hasIndex: Boolean,
    val hasPhp: Boolean
)

private fun findLanAddress(): String? {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    !address.hostAddress.orEmpty().startsWith("169.254.")
                ) return address.hostAddress
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

private fun copyText(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("EasyHost URL", value))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyHostApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

    var rootUri by remember { mutableStateOf<Uri?>(null) }
    var sites by remember { mutableStateOf(emptyList<Site>()) }
    var selected by remember { mutableStateOf<Site?>(null) }
    var port by remember { mutableStateOf(prefs.getString("port", "8080") ?: "8080") }
    var server by remember { mutableStateOf<StaticSiteServer?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Подключите папку с проектами") }
    var showSettings by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var lanAddress by remember { mutableStateOf(findLanAddress()) }

    fun scanFolder(uri: Uri, restoreRecent: Boolean = false) {
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalStateException("Не удалось открыть выбранную папку")
        if (!root.exists() || !root.isDirectory) {
            throw IllegalStateException("Папка недоступна. Подключите её заново.")
        }

        val found = root.listFiles()
            .filter { it.isDirectory }
            .map { dir ->
                val children = dir.listFiles()
                Site(
                    name = dir.name ?: "Без названия",
                    documentUri = dir.uri,
                    rootUri = uri,
                    hasIndex = children.any { it.name.equals("index.html", true) || it.name.equals("index.htm", true) },
                    hasPhp = children.any { it.name.equals("index.php", true) }
                )
            }
            .sortedBy { it.name.lowercase() }

        sites = found
        val savedProjectUri = prefs.getString("recent_project_uri", null)
        selected = if (restoreRecent && savedProjectUri != null) {
            found.firstOrNull { it.documentUri.toString() == savedProjectUri } ?: found.firstOrNull()
        } else {
            selected?.let { old -> found.firstOrNull { it.documentUri == old.documentUri } }
        }
        if (selected == null && found.isNotEmpty()) selected = found.first()
    }

    fun forgetFolder() {
        rootUri = null
        sites = emptyList()
        selected = null
        prefs.edit()
            .remove("root_uri")
            .remove("recent_project_uri")
            .apply()
        status = "Подключите папку с проектами"
    }

    LaunchedEffect(Unit) {
        val saved = prefs.getString("root_uri", null)?.let(Uri::parse)
        if (saved != null) {
            val permissionStillExists = context.contentResolver.persistedUriPermissions.any {
                it.uri == saved && it.isReadPermission
            }
            if (!permissionStillExists) {
                forgetFolder()
                status = "Доступ к папке потерян. Выберите её ещё раз."
            } else {
                try {
                    rootUri = saved
                    scanFolder(saved, restoreRecent = true)
                    status = "Последняя папка восстановлена"
                } catch (_: Exception) {
                    forgetFolder()
                    status = "Не удалось восстановить папку. Подключите её заново."
                }
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: SecurityException) {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                scanFolder(uri, restoreRecent = true)
                rootUri = uri
                prefs.edit().putString("root_uri", uri.toString()).apply()
                status = "Папка подключена и сохранена"
            } catch (e: Exception) {
                status = e.message ?: "Не удалось подключить папку"
            }
        }
    }

    val localUrl = "http://127.0.0.1:$port"
    val lanUrl = lanAddress?.let { "http://$it:$port" }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Panel,
            primary = Accent,
            onPrimary = Color(0xFF11170D),
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Muted,
            secondary = Accent
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // Шапка
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "EasyHost",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1.1).sp
                        )
                        Text(
                            "LOCAL WEB SERVER",
                            color = Muted,
                            fontSize = 10.sp,
                            letterSpacing = 2.2.sp
                        )
                    }
                    Surface(color = Panel, shape = CircleShape) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Настройки", tint = Muted)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Карточка сервера
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotScale by animateFloatAsState(
                                targetValue = if (running) 1.18f else 1f,
                                animationSpec = tween(500),
                                label = "server-indicator"
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .scale(dotScale)
                                    .background(if (running) Accent else Muted, CircleShape)
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                if (running) "СЕРВЕР АКТИВЕН" else "СЕРВЕР ОСТАНОВЛЕН",
                                color = if (running) Accent else Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.15.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Text("PORT  $port", color = Muted, fontSize = 10.sp)
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (running) localUrl else "Готов к запуску",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(status, color = Muted, fontSize = 12.sp)

                        AnimatedVisibility(
                            visible = running,
                            enter = fadeIn(tween(220)) + slideInVertically(initialOffsetY = { it / 5 }),
                            exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it / 5 })
                        ) {
                            Column {
                                Spacer(Modifier.height(15.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Button(
                                        onClick = {
                                            try {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(localUrl))
                                                )
                                            } catch (_: Exception) {
                                                status = "Не удалось открыть браузер"
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(vertical = 13.dp)
                                    ) {
                                        Icon(Icons.Rounded.Wifiguage, contentDescription = null)
                                        Spacer(Modifier.width(7.dp))
                                        Text("Открыть сайт")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            copyText(context, localUrl)
                                            copied = true
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(vertical = 13.dp)
                                    ) {
                                        Icon(
                                            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(if (copied) "Скопировано" else "Копировать")
                                    }
                                }

                                if (lanUrl != null) {
                                    Spacer(Modifier.height(13.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Rounded.Wifi,
                                            contentDescription = null,
                                            tint = Muted,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "ДОСТУП ПО WI-FI",
                                                color = Muted,
                                                fontSize = 9.sp,
                                                letterSpacing = 1.sp
                                            )
                                            Text(lanUrl, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                        IconButton(onClick = {
                                            copyText(context, lanUrl)
                                            copied = true
                                        }) {
                                            Icon(Icons.Rounded.ContentCopy, null, tint = Accent)
                                        }
                                    }
                                    Text(
                                        "Откройте адрес на другом устройстве в той же сети.",
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(17.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val site = selected
                                    val parsedPort = port.toIntOrNull()
                                    when {
                                        site == null -> status = "Сначала выберите проект"
                                        parsedPort == null || parsedPort !in 1024..65535 ->
                                            status = "Порт должен быть от 1024 до 65535"
                                        !site.hasIndex -> status = "В проекте не найден index.html"
                                        else -> {
                                            try {
                                                server?.stop()
                                                server = StaticSiteServer(
                                                    context,
                                                    site.rootUri,
                                                    site.name,
                                                    parsedPort
                                                ).also { it.start() }
                                                running = true
                                                status = "Раздаётся проект «${site.name}»"
                                                prefs.edit()
                                                    .putString("recent_project_uri", site.documentUri.toString())
                                                    .putString("port", port)
                                                    .apply()
                                                lanAddress = findLanAddress()
                                                copied = false
                                                showServerNotification(context, port, site.name)
                                            } catch (e: Exception) {
                                                running = false
                                                server = null
                                                hideServerNotification(context)
                                                status = e.message ?: "Ошибка запуска сервера"
                                            }
                                        }
                                    }
                                },
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = Color(0xFF11170D)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (selected != null && selected?.documentUri.toString() ==
                                        prefs.getString("recent_project_uri", null)
                                    ) "Быстрый запуск" else "Запустить",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    server?.stop()
                                    server = null
                                    running = false
                                    status = "Сервер остановлен"
                                    hideServerNotification(context)
                                },
                                enabled = running,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Остановить")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(21.dp))

                // Список проектов
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "МОИ ПРОЕКТЫ",
                            color = Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            "${sites.size} ${if (sites.size == 1) "проект" else "проектов"}",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                    TextButton(
                        onClick = {
                            rootUri?.let {
                                try {
                                    scanFolder(it)
                                    status = "Список проектов обновлён"
                                } catch (_: Exception) {
                                    forgetFolder()
                                    status = "Папка недоступна. Подключите её заново."
                                }
                            }
                        },
                        enabled = rootUri != null
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = Accent, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Обновить", color = Accent)
                    }
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Выбрать папку", tint = Accent)
                    }
                }

                if (rootUri == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(21.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(23.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier.size(54.dp).background(PanelSoft, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.FolderOpen, null, tint = Accent, modifier = Modifier.size(27.dp))
                            }
                            Spacer(Modifier.height(13.dp))
                            Text("Папка ещё не подключена", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "Выберите папку, внутри которой находятся папки ваших сайтов.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(15.dp))
                            Button(
                                onClick = { folderPicker.launch(null) },
                                shape = RoundedCornerShape(13.dp)
                            ) {
                                Icon(Icons.Rounded.FolderOpen, null)
                                Spacer(Modifier.width(7.dp))
                                Text("Выбрать папку")
                            }
                        }
                    }
                } else if (sites.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(19.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(19.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Rounded.Folder, null, tint = Muted, modifier = Modifier.size(29.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Проектов пока нет", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Создайте папку сайта внутри выбранной директории.",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        contentPadding = PaddingValues(bottom = 10.dp)
                    ) {
                        items(sites, key = { it.documentUri.toString() }) { site ->
                            val active = selected?.documentUri == site.documentUri
                            val cardColor by animateColorAsState(
                                targetValue = if (active) AccentSoft else Panel,
                                animationSpec = tween(190),
                                label = "project-card-color"
                            )
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = site
                                        prefs.edit()
                                            .putString("recent_project_uri", site.documentUri.toString())
                                            .apply()
                                        status = "Выбран проект «${site.name}»"
                                        copied = false
                                    },
                                colors = CardDefaults.cardColors(containerColor = cardColor),
                                shape = RoundedCornerShape(17.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(if (active) Color(0xFF344A32) else PanelSoft, RoundedCornerShape(13.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Folder,
                                            contentDescription = null,
                                            tint = if (active) Accent else Muted
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(site.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(
                                            when {
                                                site.hasIndex -> "HTML · готов к запуску"
                                                site.hasPhp -> "PHP · нужен PHP runtime"
                                                else -> "Не найден index.html"
                                            },
                                            color = Muted,
                                            fontSize = 10.sp
                                        )
                                    }
                                    if (active) {
                                        Icon(Icons.Rounded.Check, null, tint = Accent)
                                    } else {
                                        Text("ВЫБРАТЬ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                if (sites.isEmpty()) Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Language, null, tint = Muted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("HTML  ·  CSS  ·  JavaScript  ·  LAN", color = Muted, fontSize = 10.sp)
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
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            placeholder = { Text("8080") }
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "Допустимый диапазон: 1024–65535. Порт сохраняется при нажатии «Готово».",
                            color = Muted,
                            fontSize = 11.sp
                        )
                        if (rootUri != null) {
                            Spacer(Modifier.height(15.dp))
                            OutlinedButton(
                                onClick = {
                                    forgetFolder()
                                    showSettings = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Отключить сохранённую папку")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = port.toIntOrNull()
                        if (value != null && value in 1024..65535) {
                            prefs.edit().putString("port", port).apply()
                            showSettings = false
                        } else {
                            status = "Порт должен быть от 1024 до 65535"
                        }
                    }) { Text("Готово") }
                }
            )
        }
    }
}
