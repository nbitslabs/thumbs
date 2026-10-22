package com.nbitslabs.thumbs.view

import android.graphics.Bitmap
import com.nbitslabs.thumbs.a11y.CoordinateSpace
import com.nbitslabs.thumbs.a11y.ThumbsAccessibilityService
import com.nbitslabs.thumbs.auth.ApiKeyStore
import com.nbitslabs.thumbs.mcp.ToolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A JPEG of the screen in the coordinate space. */
class Frame(val jpeg: ByteArray, val at: Long)

/** A view-only link. [keyId] is the API key that created it, or null for links made in the app. */
class ViewToken(val keyId: String?, val label: String, val expiresAt: Long)

/**
 * The live view: a low frame rate MJPEG stream of the screen for people (or agents) who want to
 * watch an agent work, opened in a browser through short-lived view-only links.
 *
 * Frames come from the same accessibility screenshots agents use. Android allows about three
 * per second, so this is a slideshow rather than video. Capturing only happens while someone is
 * watching, and screenshots taken by agents are shared with viewers instead of captured twice.
 */
class LiveView(private val keys: ApiKeyStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val tokens = ConcurrentHashMap<String, ViewToken>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    private val _frame = MutableStateFlow<Frame?>(null)

    private val _viewers = MutableStateFlow(0)
    /** Number of open streams, shown in the notification and the app. */
    val viewers: StateFlow<Int> = _viewers.asStateFlow()

    fun createLink(keyId: String?, label: String, validForMs: Long): Pair<String, ViewToken> {
        val token = buildString {
            append(TOKEN_PREFIX)
            repeat(32) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        val info = ViewToken(keyId, label, clock() + validForMs)
        tokens[token] = info
        return token to info
    }

    /** The link's details if it's still valid: not expired, and its API key not revoked or expired. */
    fun validate(token: String?): ViewToken? {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) return null
        val info = tokens[token] ?: return null
        val now = clock()
        val keyOk = info.keyId == null || keys.keys.value.any { it.id == info.keyId && !it.isExpired(now) }
        if (now >= info.expiresAt || !keyOk) {
            tokens.remove(token)
            return null
        }
        return info
    }

    /** Invalidates every link; open streams end at their next frame. */
    fun endAll() = tokens.clear()

    /** Shares a screenshot an agent just took, so viewers see it without another capture. */
    fun offer(jpeg: ByteArray) {
        if (_viewers.value > 0) _frame.value = Frame(jpeg, clock())
    }

    /**
     * Sends frames to [send] while the link stays valid and the client keeps reading. Returns
     * when the link is revoked or expires; throws when the client goes away.
     */
    suspend fun stream(token: String, send: suspend (Frame) -> Unit) {
        _viewers.update { it + 1 }
        ensureCapturing()
        try {
            _frame.filterNotNull().takeWhile { validate(token) != null }.collect { send(it) }
        } finally {
            _viewers.update { it - 1 }
        }
    }

    @Synchronized
    private fun ensureCapturing() {
        if (captureJob?.isActive == true) return
        captureJob = scope.launch {
            while (isActive && _viewers.value > 0) {
                val last = _frame.value?.at ?: 0
                val wait = FRAME_INTERVAL_MS - (clock() - last)
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                val svc = ThumbsAccessibilityService.instance
                val jpeg = try {
                    svc?.screenshot()?.let(::encode)
                } catch (e: ToolException) {
                    null
                }
                if (jpeg != null) _frame.value = Frame(jpeg, clock()) else delay(RETRY_MS)
            }
        }
    }

    private fun encode(raw: Bitmap): ByteArray {
        val space = CoordinateSpace.forScreen(raw.width, raw.height)
        val scaled = if (space.scale < 1.0) Bitmap.createScaledBitmap(raw, space.width, space.height, true) else raw
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== raw) scaled.recycle()
        raw.recycle()
        return out.toByteArray()
    }

    companion object {
        const val TOKEN_PREFIX = "view_"
        const val MAX_VIEWERS = 4
        private const val FRAME_INTERVAL_MS = 400L
        private const val RETRY_MS = 1000L
        private const val JPEG_QUALITY = 60
        private val random = SecureRandom()
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
