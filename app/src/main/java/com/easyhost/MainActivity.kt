package com.easyhost

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4102)
        }
        setContent { EasyHostApp() }
    }

}

private const val PREFS = "easyhost_settings"

data class Site(val name: String, val uri: Uri, val hasIndex: Boolean, val hasPhp: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyHostApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var rootUri by remember { mutableStateOf(prefs.getString("root_uri", null)?.let(Uri::parse)) }
    var sites by remember { mutableStateOf(listOf<Site>()) }
    var selected by remember { mutableStateOf<Site?>(null) }
    var port by remember { mutableStateOf(prefs.getString("port", "8080") ?: "8080") }
    var server by remember { mutableStateOf<StaticSiteServer?>(null) }
    var running by remember { mutableStateOf(prefs.getBoolean("server_running", false)) }
    var status by remember { mutableStateOf("Выберите папку Host") }
    var showSettings by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    ServerForegroundService.ACTION_STARTED -> {
                        running = true
                        status = "Сервер запущен"
                    }
                    ServerForegroundService.ACTION_FAILED -> {
                        running = false
                        status = intent?.getStringExtra(ServerForegroundService.EXTRA_ERROR) ?: "Ошибка запуска"
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ServerForegroundService.ACTION_STARTED)
            addAction(ServerForegroundService.ACTION_FAILED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }

    fun scan(uri: Uri) {
        val root = DocumentFile.fromTreeUri(context, uri)
        sites = root?.listFiles()?.filter { it.isDirectory }?.map { dir ->
            val children = dir.listFiles()
            Site(dir.name ?: "Без названия", uri,
                children.any { it.name.equals("index.html", true) },
                children.any { it.name.equals("index.php", true) })
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        selected = sites.firstOrNull { it.name == prefs.getString("selected_project", null) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { context.contentResolver.takePersistableUriPermission(uri, flags) }
            catch (_: SecurityException) {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            rootUri = uri
            prefs.edit().putString("root_uri", uri.toString()).apply()
            scan(uri)
            status = "Папка подключена"
        }
    }

    LaunchedEffect(rootUri) {
        rootUri?.let { uri ->
            try { scan(uri) }
            catch (_: Exception) {
                rootUri = null
                prefs.edit().remove("root_uri").remove("selected_project").apply()
                status = "Доступ к папке потерян. Подключите её заново."
            }
        }
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
                        Text("Изи Хост", fontSize = 29.sp, fontWeight = FontWeight.Bold)
                        Text("LOCAL DEVELOPMENT", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
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
                            Text(if (running) "СЕРВЕР РАБОТАЕТ" else "СЕРВЕР ОСТАНОВЛЕН",
                                color = if (running) Accent else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(if (running) "http://127.0.0.1:$port" else "Готов к запуску",
                            fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(status, color = Muted, fontSize = 12.sp)
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
                                            prefs.edit().putString("selected_project", site.name).putString("port", port).apply()
                                            val serviceIntent = Intent(context, ServerForegroundService::class.java).apply {
                                                action = ServerForegroundService.ACTION_START
                                                putExtra(ServerForegroundService.EXTRA_TREE, site.uri.toString())
                                                putExtra(ServerForegroundService.EXTRA_PROJECT, site.name)
                                                putExtra(ServerForegroundService.EXTRA_PORT, p)
                                            }
                                            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, serviceIntent)
                                            else context.startService(serviceIntent)
                                            running = true
                                            status = "Запуск ${site.name}…"
                                        } catch (e: Exception) {
                                            running = false
                                            status = e.message ?: "Ошибка запуска"
                                        }
                                    }
                                },
                                enabled = !running,
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Запустить") }
                            OutlinedButton(onClick = {
                                context.startService(Intent(context, ServerForegroundService::class.java).setAction(ServerForegroundService.ACTION_STOP))
                                running = false
                                status = "Сервер остановлен"
                            }, enabled = running, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Стоп")
                            }
                        }
                        if (running) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val url = "http://127.0.0.1:$port"
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Не найден браузер", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Icon(Icons.Rounded.Language, null); Spacer(Modifier.width(6.dp)); Text("Открыть") }
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("EasyHost URL", "http://127.0.0.1:$port"))
                                        Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Копировать ссылку") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("МОИ ПРОЕКТЫ", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { rootUri?.let { scan(it) } }) { Text("Обновить", color = Accent) }
                }
                if (rootUri == null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Folder, null, tint = Accent, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Подключите папку с сайтами", fontWeight = FontWeight.Medium)
                            Text("Выберите Documents/Host или создайте её", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { folderPicker.launch(null) }) { Text("Выбрать папку") }
                        }
                    }
                } else if (sites.isEmpty()) {
                    Text("Пока нет проектов. Создайте папку сайта внутри Host.", color = Muted, fontSize = 13.sp)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(sites) { site ->
                            val active = selected?.uri == site.uri
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selected = site
                                    prefs.edit().putString("selected_project", site.name).apply()
                                },
                                colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFF252B25) else Panel),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Folder, null, tint = if (active) Accent else Muted)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(site.name, fontWeight = FontWeight.SemiBold)
                                        Text(buildString {
                                            if (site.hasIndex) append("HTML")
                                            if (site.hasIndex && site.hasPhp) append("  ·  ")
                                            if (site.hasPhp) append("PHP")
                                            if (!site.hasIndex && !site.hasPhp) append("Нет index.html / index.php")
                                        }, color = Muted, fontSize = 11.sp)
                                    }
                                    if (active) Text("ВЫБРАН", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Language, null, tint = Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Локальный хостинг · HTML / CSS / JS", color = Muted, fontSize = 10.sp)
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
                        OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Text("Диапазон: 1024–65535", color = Muted, fontSize = 11.sp)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    prefs.edit().putString("port", port).apply()
                    showSettings = false
                }) { Text("Готово") } }
            )
        }
    }
}
