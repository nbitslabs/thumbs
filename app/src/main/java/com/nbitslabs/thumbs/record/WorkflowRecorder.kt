package com.nbitslabs.thumbs.record

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Semantic representation of a single user interaction captured during "watch me do it once"
 * workflow recording. Passwords, OTPs, and raw typed text are never stored.
 */
@Serializable
data class RecordedStep(
    val index: Int,
    val offsetMs: Long,
    val type: String,
    val packageName: String,
    val label: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val textLength: Int? = null,
    val redacted: Boolean = false,
)

@Serializable
data class RecordedWorkflow(
    val id: String,
    val name: String,
    val startedAt: Long,
    val stoppedAt: Long,
    val steps: List<RecordedStep>,
)

/**
 * Pure JVM-friendly intermediate event so recording, redaction, and deduplication logic can be
 * thoroughly unit-tested without Android framework mocks.
 */
data class RawUiEvent(
    val type: String,
    val packageName: String,
    val label: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val isPassword: Boolean = false,
    val textLength: Int? = null,
)

/**
 * Records semantic UI steps from accessibility events while excluding Thumbs itself and
 * redacting sensitive fields by design.
 */
class WorkflowRecorder(
    private val dir: File,
    private val selfPackage: String = "com.nbitslabs.thumbs",
    private val maxSteps: Int = 200,
    private val maxWorkflows: Int = 30,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _activeName = MutableStateFlow<String?>(null)
    val activeName: StateFlow<String?> = _activeName.asStateFlow()

    private val _activeStepCount = MutableStateFlow(0)
    val activeStepCount: StateFlow<Int> = _activeStepCount.asStateFlow()

    private val _workflows = MutableStateFlow(loadAll())
    val workflows: StateFlow<List<RecordedWorkflow>> = _workflows.asStateFlow()

    private var activeId: String? = null
    private var activeStartedAt: Long = 0L
    private val activeSteps = mutableListOf<RecordedStep>()
    private var lastStepWallMs: Long = 0L

    fun start(name: String? = null): String {
        synchronized(lock) {
            if (_recording.value) {
                throw IllegalStateException("Workflow recording is already active")
            }
            val now = clock()
            val id = "wf_${now}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LEN)
                ?: "Workflow $id"
            activeId = id
            activeStartedAt = now
            activeSteps.clear()
            lastStepWallMs = 0L
            _activeName.value = cleanName
            _activeStepCount.value = 0
            _recording.value = true
            return id
        }
    }

    fun stop(): RecordedWorkflow? {
        val workflow = synchronized(lock) {
            if (!_recording.value) return null
            val id = activeId ?: return null
            val name = _activeName.value ?: id
            val startedAt = activeStartedAt
            val stoppedAt = maxOf(startedAt, clock())
            val wf = RecordedWorkflow(
                id = id,
                name = name,
                startedAt = startedAt,
                stoppedAt = stoppedAt,
                steps = activeSteps.toList(),
            )
            activeId = null
            activeStartedAt = 0L
            activeSteps.clear()
            lastStepWallMs = 0L
            _recording.value = false
            _activeName.value = null
            _activeStepCount.value = 0

            saveWorkflow(wf)
            trimOldestLocked()
            _workflows.value = loadAllLocked()
            wf
        }
        return workflow
    }

    fun recordRaw(event: RawUiEvent) {
        synchronized(lock) {
            if (!_recording.value) return
            val pkg = event.packageName.trim()
            if (pkg.isEmpty() || pkg == selfPackage) return
            if (event.type !in VALID_TYPES) return

            val now = clock()
            val offsetMs = (now - activeStartedAt).coerceAtLeast(0L)
            val sensitive = event.isPassword || isSensitiveField(event.viewId, event.label, event.contentDescription)

            val cleanViewId = event.viewId?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ID_LEN)
            val cleanClass = event.className?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_CLASS_LEN)

            val stepLabel: String?
            val stepContentDesc: String?
            val stepTextLen: Int?

            when {
                sensitive -> {
                    stepLabel = "[redacted]"
                    stepContentDesc = null
                    stepTextLen = null
                }
                event.type == TYPE_TEXT_INPUT -> {
                    // Never store raw typed text in recordings, even for non-password inputs
                    stepLabel = "[text input]"
                    stepContentDesc = event.contentDescription?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT_LEN)
                    stepTextLen = event.textLength?.coerceAtLeast(0)
                }
                else -> {
                    stepLabel = event.label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT_LEN)
                    stepContentDesc = event.contentDescription?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT_LEN)
                    stepTextLen = null
                }
            }

            val last = activeSteps.lastOrNull()
            if (last != null && now - lastStepWallMs <= DEDUP_WINDOW_MS) {
                when {
                    event.type == TYPE_APP_SWITCH && last.type == TYPE_APP_SWITCH && last.packageName == pkg -> {
                        return
                    }
                    event.type == TYPE_SCROLL && last.type == TYPE_SCROLL &&
                        last.packageName == pkg && last.viewId == cleanViewId && last.className == cleanClass -> {
                        return
                    }
                    event.type == TYPE_TEXT_INPUT && last.type == TYPE_TEXT_INPUT &&
                        last.packageName == pkg && last.viewId == cleanViewId && last.className == cleanClass -> {
                        val mergedRedacted = last.redacted || sensitive
                        activeSteps[activeSteps.lastIndex] = last.copy(
                            offsetMs = offsetMs,
                            label = if (mergedRedacted) "[redacted]" else "[text input]",
                            contentDescription = if (mergedRedacted) null else (stepContentDesc ?: last.contentDescription),
                            textLength = if (mergedRedacted) null else (stepTextLen ?: last.textLength),
                            redacted = mergedRedacted,
                        )
                        lastStepWallMs = now
                        return
                    }
                }
            }

            if (activeSteps.size >= maxSteps) return

            val step = RecordedStep(
                index = activeSteps.size,
                offsetMs = offsetMs,
                type = event.type,
                packageName = pkg,
                label = stepLabel,
                contentDescription = stepContentDesc,
                viewId = cleanViewId,
                className = cleanClass,
                textLength = stepTextLen,
                redacted = sensitive,
            )
            activeSteps.add(step)
            lastStepWallMs = now
            _activeStepCount.value = activeSteps.size
        }
    }

    fun recordAccessibilityEvent(event: AccessibilityEvent, currentPackage: String?) {
        if (!_recording.value) return
        runCatching {
            val eventType = event.eventType
            val rawPkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: currentPackage
            if (rawPkg.isNullOrBlank() || rawPkg == selfPackage) return

            val rawClass = event.className?.toString()
            when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (rawClass != null &&
                        (rawClass.startsWith("android.widget.") || rawClass.startsWith("android.inputmethodservice."))
                    ) {
                        return
                    }
                    recordRaw(
                        RawUiEvent(
                            type = TYPE_APP_SWITCH,
                            packageName = rawPkg,
                            className = rawClass,
                        ),
                    )
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val src = runCatching { event.source }.getOrNull()
                    val hint = src?.hintText?.toString()?.takeIf { it.isNotBlank() }
                    val editable = src?.isEditable == true
                    val rawLabel = src?.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: event.text?.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
                    val rawDesc = src?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: event.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    val viewId = src?.viewIdResourceName
                    val isPwd = event.isPassword || (src?.isPassword == true) ||
                        isSensitiveField(viewId, rawLabel, rawDesc, hint)
                    val label = when {
                        isPwd -> null
                        editable -> hint
                        else -> rawLabel
                    }
                    val desc = if (isPwd) null else rawDesc
                    recordRaw(
                        RawUiEvent(
                            type = TYPE_TAP,
                            packageName = rawPkg,
                            label = label,
                            contentDescription = desc,
                            viewId = viewId,
                            className = src?.className?.toString() ?: rawClass,
                            isPassword = isPwd,
                        ),
                    )
                }

                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    val src = runCatching { event.source }.getOrNull()
                    recordRaw(
                        RawUiEvent(
                            type = TYPE_SCROLL,
                            packageName = rawPkg,
                            contentDescription = src?.contentDescription?.toString()
                                ?: event.contentDescription?.toString(),
                            viewId = src?.viewIdResourceName,
                            className = src?.className?.toString() ?: rawClass,
                        ),
                    )
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val src = runCatching { event.source }.getOrNull()
                    val hint = src?.hintText?.toString()?.takeIf { it.isNotBlank() }
                    val rawDesc = src?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    val viewId = src?.viewIdResourceName
                    val isPwd = event.isPassword || (src?.isPassword == true) ||
                        isSensitiveField(viewId, null, rawDesc, hint)
                    val len = if (isPwd) {
                        null
                    } else {
                        event.text?.takeIf { it.isNotEmpty() }?.sumOf { it.length } ?: src?.text?.length
                    }
                    recordRaw(
                        RawUiEvent(
                            type = TYPE_TEXT_INPUT,
                            packageName = rawPkg,
                            contentDescription = if (isPwd) null else (rawDesc ?: hint),
                            viewId = viewId,
                            className = src?.className?.toString() ?: rawClass,
                            isPassword = isPwd,
                            textLength = len,
                        ),
                    )
                }
            }
        }
    }

    fun list(): List<RecordedWorkflow> = synchronized(lock) { _workflows.value }

    fun get(id: String): RecordedWorkflow? {
        if (!SAFE_ID_REGEX.matches(id)) return null
        return synchronized(lock) {
            _workflows.value.firstOrNull { it.id == id } ?: readWorkflowFile(File(dir, "$id.json"))
        }
    }

    fun delete(id: String): Boolean {
        if (!SAFE_ID_REGEX.matches(id)) return false
        return synchronized(lock) {
            val file = File(dir, "$id.json")
            val existed = file.exists() && file.delete()
            if (existed) {
                _workflows.value = loadAllLocked()
            }
            existed
        }
    }

    fun formatAsMarkdown(wf: RecordedWorkflow): String = buildString {
        val durationSec = ((wf.stoppedAt - wf.startedAt).coerceAtLeast(0L)) / 1000.0
        appendLine("# Workflow: ${wf.name}")
        appendLine("- ID: `${wf.id}`")
        appendLine("- Duration: ${String.format(Locale.US, "%.1f", durationSec)}s")
        appendLine("- Steps: ${wf.steps.size}")
        appendLine()
        if (wf.steps.isEmpty()) {
            appendLine("(No steps recorded)")
            return@buildString
        }
        for (step in wf.steps) {
            val offsetSec = step.offsetMs / 1000.0
            append("${step.index + 1}. `+${String.format(Locale.US, "%.1f", offsetSec)}s` **${step.type}** in `${step.packageName}`")
            if (step.redacted) {
                append(" — [redacted]")
            } else {
                step.label?.let { append(" — \"$it\"") }
                step.textLength?.let { append(" (length=$it)") }
            }
            step.contentDescription?.takeIf { !step.redacted && it != step.label }?.let {
                append(" desc=\"$it\"")
            }
            step.viewId?.let { append(" id=`$it`") }
            appendLine()
        }
    }.trimEnd()

    private fun saveWorkflow(wf: RecordedWorkflow) {
        dir.mkdirs()
        val target = File(dir, "${wf.id}.json")
        val tmp = File(dir, "${wf.id}.json.tmp")
        tmp.writeText(json.encodeToString(RecordedWorkflow.serializer(), wf))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun trimOldestLocked() {
        if (maxWorkflows <= 0) return
        val all = loadAllLocked()
        if (all.size <= maxWorkflows) return
        for (stale in all.drop(maxWorkflows)) {
            File(dir, "${stale.id}.json").delete()
        }
    }

    private fun loadAll(): List<RecordedWorkflow> = synchronized(lock) { loadAllLocked() }

    private fun loadAllLocked(): List<RecordedWorkflow> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull(::readWorkflowFile)
            .sortedWith(compareByDescending<RecordedWorkflow> { it.startedAt }.thenByDescending { it.stoppedAt })
    }

    private fun readWorkflowFile(file: File): RecordedWorkflow? {
        if (!file.isFile) return null
        return runCatching {
            val wf = json.decodeFromString(RecordedWorkflow.serializer(), file.readText())
            if (SAFE_ID_REGEX.matches(wf.id)) wf else null
        }.getOrNull()
    }

    companion object {
        const val TYPE_APP_SWITCH = "app_switch"
        const val TYPE_TAP = "tap"
        const val TYPE_SCROLL = "scroll"
        const val TYPE_TEXT_INPUT = "text_input"

        private val VALID_TYPES = setOf(TYPE_APP_SWITCH, TYPE_TAP, TYPE_SCROLL, TYPE_TEXT_INPUT)
        private const val DEDUP_WINDOW_MS = 1500L
        private const val MAX_NAME_LEN = 120
        private const val MAX_TEXT_LEN = 120
        private const val MAX_ID_LEN = 160
        private const val MAX_CLASS_LEN = 120
        private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{4,64}$")

        private val SENSITIVE_TOKENS = setOf(
            "password", "passwd", "passcode", "passphrase",
            "pin", "pincode", "otp", "cvv", "cvc", "totp", "2fa", "secret",
        )
        private val CAMEL_OR_DELIM = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[^a-zA-Z0-9]+")
        private val SENSITIVE_PHRASES = listOf(
            "security code",
            "verification code",
            "one time code",
            "one-time code",
            "one time password",
            "one-time password",
            "pass code",
        )

        internal fun isSensitiveField(
            viewId: String?,
            label: String?,
            contentDescription: String?,
            hintText: String? = null,
        ): Boolean {
            val fields = listOfNotNull(viewId, label, contentDescription, hintText)
            if (fields.isEmpty()) return false
            for (raw in fields) {
                val lower = raw.lowercase()
                if (SENSITIVE_PHRASES.any { lower.contains(it) }) return true
                val tokens = raw.split(CAMEL_OR_DELIM)
                for (token in tokens) {
                    if (token.isNotEmpty() && token.lowercase() in SENSITIVE_TOKENS) return true
                }
            }
            return false
        }
    }
}
