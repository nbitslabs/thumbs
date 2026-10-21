package com.nbitslabs.thumbs.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nbitslabs.thumbs.ThumbsApp
import com.nbitslabs.thumbs.a11y.ThumbsAccessibilityService
import com.nbitslabs.thumbs.auth.ApiKey
import com.nbitslabs.thumbs.auth.CreatedKey
import com.nbitslabs.thumbs.auth.Scope
import com.nbitslabs.thumbs.device.NotificationListener
import com.nbitslabs.thumbs.mcp.AuditEvent
import com.nbitslabs.thumbs.server.AccessPolicy
import com.nbitslabs.thumbs.server.NetworkMode
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = ThumbsApp.get(this)
        app.startServerIfEnabled()
        setContent { ThumbsTheme { MainScreen(app) } }
    }
}

@Composable
private fun ThumbsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class Setup(
    val accessibility: Boolean,
    val notificationAccess: Boolean,
    val batteryUnrestricted: Boolean,
    val notificationsAllowed: Boolean,
)

private fun readSetup(context: Context): Setup {
    val nm = context.getSystemService(NotificationManager::class.java)
    return Setup(
        accessibility = ThumbsAccessibilityService.instance != null,
        notificationAccess = nm.isNotificationListenerAccessGranted(ComponentName(context, NotificationListener::class.java)),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        notificationsAllowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(app: ThumbsApp) {
    val context = LocalContext.current
    var setup by remember { mutableStateOf(readSetup(context)) }
    var addresses by remember { mutableStateOf(AccessPolicy.localAddresses()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        setup = readSetup(context)
        addresses = AccessPolicy.localAddresses()
    }
    val a11yConnected by ThumbsAccessibilityService.connected.collectAsStateWithLifecycle()
    LaunchedEffect(a11yConnected) { setup = readSetup(context) }

    val status by app.status.collectAsStateWithLifecycle()
    val enabled by app.settings.serverEnabled.collectAsStateWithLifecycle()
    val port by app.settings.port.collectAsStateWithLifecycle()
    val networkMode by app.settings.networkMode.collectAsStateWithLifecycle()
    val autoWake by app.settings.autoWake.collectAsStateWithLifecycle()
    val keys by app.keys.keys.collectAsStateWithLifecycle()
    val audit by app.audit.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<CreatedKey?>(null) }
    var deleting by remember { mutableStateOf<ApiKey?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setup = readSetup(context)
    }

    val tailscale = addresses.filter(AccessPolicy::isTailscale)
    val urlHost = tailscale.firstOrNull { it is java.net.Inet4Address }?.hostAddress ?: tailscale.firstOrNull()?.let { "[${it.hostAddress}]" }
    val url = "http://${urlHost ?: "<tailscale-ip>"}:$port/mcp"

    Scaffold(topBar = { TopAppBar(title = { Text("Thumbs") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Section("Server") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    status.running -> "Running on port ${status.port}"
                                    enabled -> "Starting…"
                                    else -> "Stopped"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        Switch(checked = enabled, onCheckedChange = { if (it) app.startServer() else app.stopServer() })
                    }
                    Spacer(Modifier.size(8.dp))
                    if (tailscale.isEmpty()) {
                        Text(
                            "Tailscale isn't connected on this phone. Open Tailscale and connect so your computers can reach it.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("MCP endpoint", style = MaterialTheme.typography.labelMedium)
                        CopyableMono(url)
                    }
                    if (networkMode == NetworkMode.ANY) {
                        val others = addresses.filterNot(AccessPolicy::isTailscale).mapNotNull { it.hostAddress }
                        if (others.isNotEmpty()) {
                            Text("Also reachable on: ${others.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (status.lastClient != null) {
                        Text(
                            "Last request: ${status.lastClient} at ${DateFormat.getTimeInstance().format(Date(status.lastRequestAt))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item {
                Section("Setup") {
                    SetupRow(
                        done = setup.accessibility,
                        title = "Accessibility service",
                        detail = "Required to see and control the screen. If the toggle is greyed out, open App info → ⋮ → Allow restricted settings first.",
                        action = "Open settings",
                    ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    SetupRow(
                        done = setup.notificationAccess,
                        title = "Notification access",
                        detail = "Lets agents read, open, reply to and dismiss notifications.",
                        action = "Grant",
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                ComponentName(context, NotificationListener::class.java).flattenToString(),
                            ),
                        )
                    }
                    SetupRow(
                        done = setup.batteryUnrestricted,
                        title = "Unrestricted battery",
                        detail = "Keeps the server reachable while the phone is idle (Doze cuts network access otherwise). Do the same for Tailscale.",
                        action = "Allow",
                    ) { requestBatteryExemption(context) }
                    SetupRow(
                        done = setup.notificationsAllowed,
                        title = "Status notification",
                        detail = "Shows when the server is running and when an agent is active, with a Stop button.",
                        action = "Allow",
                    ) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }

            item {
                Section("API keys", trailing = {
                    IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, contentDescription = "New key") }
                }) {
                    if (keys.isEmpty()) {
                        Text("No keys yet. Create one for each computer or agent that should control this phone.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = { showCreate = true }) { Text("Create key") }
                    }
                    keys.forEach { key ->
                        KeyRow(key) { deleting = key }
                    }
                }
            }

            item {
                Section("Settings") {
                    Text("Accept connections from", style = MaterialTheme.typography.labelMedium)
                    RadioRow("Tailscale only (recommended)", networkMode == NetworkMode.TAILSCALE_ONLY) { app.settings.setNetworkMode(NetworkMode.TAILSCALE_ONLY) }
                    RadioRow("Any network (Wi-Fi/LAN too; still needs a key)", networkMode == NetworkMode.ANY) { app.settings.setNetworkMode(NetworkMode.ANY) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Wake screen for actions")
                            Text("Turn the screen on automatically when an agent acts.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = autoWake, onCheckedChange = app.settings::setAutoWake)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    PortSetting(port) { newPort ->
                        app.settings.setPort(newPort)
                        app.restartServer()
                    }
                }
            }

            item {
                Section("Activity") {
                    if (audit.isEmpty()) Text("Tool calls made by agents will appear here.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(audit.take(100), key = { "${it.time}-${it.tool}-${it.keyName}-${it.durationMs}" }) { AuditRow(it) }
        }
    }

    if (showCreate) {
        CreateKeyDialog(onDismiss = { showCreate = false }) { name, scope ->
            showCreate = false
            created = app.keys.create(name, scope)
        }
    }
    created?.let { CreatedKeyDialog(it, url) { created = null } }
    deleting?.let { key ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Revoke \"${key.name}\"?") },
            text = { Text("Agents using this key will immediately lose access.") },
            confirmButton = {
                TextButton(onClick = {
                    app.keys.delete(key.id)
                    deleting = null
                }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    // The app's whole purpose is to stay reachable, which is an accepted use of this intent
    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
}

@Composable
private fun Section(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing()
            }
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, detail: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (done) "Done" else "Needs attention",
            tint = if (done) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (!done) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (!done) FilledTonalButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun KeyRow(key: ApiKey, onDelete: () -> Unit) {
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(key.name, fontWeight = FontWeight.Medium)
            Text(
                "${if (key.scope == Scope.CONTROL) "Full control" else "Read only"} · ${key.prefix}… · " +
                    (key.lastUsedAt?.let { "used ${df.format(Date(it))}" } ?: "never used"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Revoke ${key.name}") }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun PortSetting(port: Int, onApply: (Int) -> Unit) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it in 1024..65535 }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            singleLine = true,
            isError = parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = parsed != null && parsed != port, onClick = { parsed?.let(onApply) }) { Text("Apply") }
    }
}

@Composable
private fun AuditRow(e: AuditEvent) {
    val tf = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row {
            Text(
                e.tool,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            Text("${tf.format(Date(e.time))} · ${e.durationMs}ms", style = MaterialTheme.typography.bodySmall)
        }
        Text(e.keyName + if (e.args.isNotEmpty()) " · ${e.args}" else "", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CopyableMono(text: String, sensitive: Boolean = false) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = { copy(context, text, sensitive) },
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
    }
}

private fun copy(context: Context, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText("Thumbs", text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreate: (String, Scope) -> Unit) {
    var name by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(Scope.CONTROL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New API key") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. laptop-claude)") }, singleLine = true)
                Spacer(Modifier.size(12.dp))
                RadioRow("Full control", scope == Scope.CONTROL) { scope = Scope.CONTROL }
                RadioRow("Read only (screen, UI tree, notifications)", scope == Scope.READ) { scope = Scope.READ }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, scope) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreatedKeyDialog(created: CreatedKey, url: String, onDismiss: () -> Unit) {
    val token = created.token
    val serverName = "thumbs"
    var tab by remember { mutableIntStateOf(0) }
    val snippets = listOf(
        "Claude Code" to "claude mcp add --transport http $serverName $url --header \"Authorization: Bearer $token\"",
        "Codex" to "# ~/.codex/config.toml\n[mcp_servers.$serverName]\nurl = \"$url\"\nhttp_headers = { \"Authorization\" = \"Bearer $token\" }",
        "Hermes / JSON" to "{\n  \"mcpServers\": {\n    \"$serverName\": {\n      \"type\": \"http\",\n      \"url\": \"$url\",\n      \"headers\": { \"Authorization\": \"Bearer $token\" }\n    }\n  }\n}",
    )
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Key \"${created.key.name}\" created") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Copy it now. It won't be shown again.", fontWeight = FontWeight.SemiBold)
                CopyableMono(token, sensitive = true)
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    snippets.forEachIndexed { i, (label, _) ->
                        if (i == tab) FilledTonalButton(onClick = {}) { Text(label, fontSize = 12.sp) }
                        else TextButton(onClick = { tab = i }) { Text(label, fontSize = 12.sp) }
                    }
                }
                CopyableMono(snippets[tab].second, sensitive = true)
                Text("Tap a box to copy.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
