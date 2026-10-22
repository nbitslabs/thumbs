package com.nbitslabs.thumbs

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nbitslabs.thumbs.auth.ApiKeyStore
import com.nbitslabs.thumbs.mcp.AuditEvent
import com.nbitslabs.thumbs.safety.ConfirmMode
import com.nbitslabs.thumbs.server.NetworkMode
import com.nbitslabs.thumbs.server.ServerService
import com.nbitslabs.thumbs.view.LiveView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class ThumbsApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var keys: ApiKeyStore
        private set
    lateinit var liveView: LiveView
        private set

    private val _audit = MutableStateFlow<List<AuditEvent>>(emptyList())
    /** Most recent tool calls, newest first. In memory only. */
    val audit: StateFlow<List<AuditEvent>> = _audit.asStateFlow()

    private val _auditEvents = MutableSharedFlow<AuditEvent>(extraBufferCapacity = 64)
    /** Each tool call as it happens, for the live view's activity feed. */
    val auditEvents: SharedFlow<AuditEvent> = _auditEvents.asSharedFlow()

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        keys = ApiKeyStore(File(filesDir, "api_keys.json"))
        liveView = LiveView(keys)
    }

    fun recordAudit(event: AuditEvent) {
        _audit.update { (listOf(event) + it).take(MAX_AUDIT) }
        _auditEvents.tryEmit(event)
    }

    fun updateStatus(block: (ServerStatus) -> ServerStatus) = _status.update(block)

    fun startServerIfEnabled() {
        if (settings.serverEnabled.value) startServer()
    }

    fun startServer(restart: Boolean = false) {
        settings.setServerEnabled(true)
        try {
            val intent = Intent(this, ServerService::class.java)
            if (restart) intent.action = ServerService.ACTION_RESTART
            startForegroundService(intent)
        } catch (e: Exception) {
            // Background FGS starts can be refused (e.g. some OEMs); the UI can start it later
            Log.w(TAG, "Couldn't start server service", e)
            updateStatus { it.copy(error = "Couldn't start in background: ${e.message}. Open the app to start the server.") }
        }
    }

    fun stopServer() {
        settings.setServerEnabled(false)
        stopService(Intent(this, ServerService::class.java))
    }

    /** Rebinds the listener, e.g. after a port change. */
    fun restartServer() {
        if (settings.serverEnabled.value) startServer(restart = true)
    }

    companion object {
        private const val TAG = "Thumbs"
        private const val MAX_AUDIT = 300
        fun get(context: Context) = context.applicationContext as ThumbsApp
    }
}

data class ServerStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val error: String? = null,
    /** Name of the key that most recently made a request, and when (wall clock). */
    val lastClient: String? = null,
    val lastRequestAt: Long = 0,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _serverEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val serverEnabled: StateFlow<Boolean> = _serverEnabled.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, com.nbitslabs.thumbs.server.HttpServer.DEFAULT_PORT))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _networkMode = MutableStateFlow(
        runCatching { NetworkMode.valueOf(prefs.getString(KEY_NETWORK, null) ?: "") }.getOrDefault(NetworkMode.TAILSCALE_ONLY),
    )
    val networkMode: StateFlow<NetworkMode> = _networkMode.asStateFlow()

    private val _autoWake = MutableStateFlow(prefs.getBoolean(KEY_AUTO_WAKE, true))
    val autoWake: StateFlow<Boolean> = _autoWake.asStateFlow()

    private val _keepAwake = MutableStateFlow(prefs.getBoolean(KEY_KEEP_AWAKE, true))
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    private val _confirmMode = MutableStateFlow(
        runCatching { ConfirmMode.valueOf(prefs.getString(KEY_CONFIRM, null) ?: "") }.getOrDefault(ConfirmMode.OFF),
    )
    /** Whether agents' actions need approval on the phone. Off by default. */
    val confirmMode: StateFlow<ConfirmMode> = _confirmMode.asStateFlow()

    fun setServerEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, v).apply()
        _serverEnabled.value = v
    }

    fun setPort(v: Int) {
        prefs.edit().putInt(KEY_PORT, v).apply()
        _port.value = v
    }

    fun setNetworkMode(v: NetworkMode) {
        prefs.edit().putString(KEY_NETWORK, v.name).apply()
        _networkMode.value = v
    }

    fun setAutoWake(v: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_WAKE, v).apply()
        _autoWake.value = v
    }

    fun setKeepAwake(v: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_AWAKE, v).apply()
        _keepAwake.value = v
    }

    fun setConfirmMode(v: ConfirmMode) {
        prefs.edit().putString(KEY_CONFIRM, v.name).apply()
        _confirmMode.value = v
    }

    private companion object {
        const val KEY_ENABLED = "server_enabled"
        const val KEY_PORT = "port"
        const val KEY_NETWORK = "network_mode"
        const val KEY_AUTO_WAKE = "auto_wake"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_CONFIRM = "confirm_mode"
    }
}
