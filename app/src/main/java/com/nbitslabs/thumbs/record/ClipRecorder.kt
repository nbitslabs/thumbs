package com.nbitslabs.thumbs.record

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.nbitslabs.thumbs.mcp.ToolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ClipMetadata(
    val id: String,
    val createdAt: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hasAudio: Boolean,
    val sizeBytes: Long,
)

/**
 * Manages bounded MP4 screen recording via an active Android [MediaProjection] session.
 * Clips are persisted under [clipsDir] as `<id>.mp4` + `<id>.json` metadata sidecars.
 */
class ClipRecorder(
    private val clipsDir: File,
    private val maxClips: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stateLock = Any()
    private val recordMutex = Mutex()

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _clips = MutableStateFlow(loadAll())
    val clips: StateFlow<List<ClipMetadata>> = _clips.asStateFlow()

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var onProjectionStopped: (() -> Unit)? = null

    fun attachProjection(projection: MediaProjection, onStopped: (() -> Unit)? = null) {
        val oldProjection: MediaProjection?
        val oldCallback: MediaProjection.Callback?
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                val stoppedCb: (() -> Unit)?
                synchronized(stateLock) {
                    if (this@ClipRecorder.projection === projection) {
                        this@ClipRecorder.projection = null
                        this@ClipRecorder.projectionCallback = null
                        _sessionReady.value = false
                        stoppedCb = this@ClipRecorder.onProjectionStopped
                        this@ClipRecorder.onProjectionStopped = null
                    } else {
                        stoppedCb = null
                    }
                }
                stoppedCb?.invoke()
            }
        }
        synchronized(stateLock) {
            oldProjection = this.projection
            oldCallback = this.projectionCallback
            this.projection = projection
            this.projectionCallback = cb
            // Replace without firing the previous callback when swapping projections.
            this.onProjectionStopped = onStopped
            _sessionReady.value = true
        }
        // Register callback before any createVirtualDisplay call (required on Android 14+)
        projection.registerCallback(cb, Handler(Looper.getMainLooper()))
        if (oldProjection != null && oldProjection !== projection) {
            if (oldCallback != null) runCatching { oldProjection.unregisterCallback(oldCallback) }
            runCatching { oldProjection.stop() }
        }
    }

    fun releaseProjection() {
        val oldProjection: MediaProjection?
        val oldCallback: MediaProjection.Callback?
        val stoppedCb: (() -> Unit)?
        synchronized(stateLock) {
            oldProjection = this.projection
            oldCallback = this.projectionCallback
            stoppedCb = this.onProjectionStopped
            this.projection = null
            this.projectionCallback = null
            this.onProjectionStopped = null
            _sessionReady.value = false
        }
        if (oldProjection != null) {
            if (oldCallback != null) runCatching { oldProjection.unregisterCallback(oldCallback) }
            runCatching { oldProjection.stop() }
        }
        stoppedCb?.invoke()
    }

    fun list(): List<ClipMetadata> = synchronized(stateLock) { _clips.value }

    fun getFile(id: String): File? {
        if (!SAFE_ID_REGEX.matches(id)) return null
        val file = File(clipsDir, "$id.mp4")
        if (file.parentFile != clipsDir || !file.isFile) return null
        return file
    }

    fun delete(id: String): Boolean {
        if (!SAFE_ID_REGEX.matches(id)) return false
        return synchronized(stateLock) {
            val mp4 = File(clipsDir, "$id.mp4")
            val meta = File(clipsDir, "$id.json")
            val deletedMp4 = mp4.exists() && mp4.delete()
            val deletedMeta = meta.exists() && meta.delete()
            if (deletedMp4 || deletedMeta) {
                _clips.value = loadAllLocked()
                true
            } else {
                false
            }
        }
    }

    suspend fun recordClip(
        context: Context,
        durationSec: Int = 10,
        fps: Int = 24,
        maxShortEdge: Int = 720,
        @Suppress("UNUSED_PARAMETER") audio: Boolean = false,
    ): ClipMetadata {
        val clampedDuration = durationSec.coerceIn(3, 30)
        val clampedFps = fps.coerceIn(15, 30)
        val clampedShortEdge = maxShortEdge.coerceIn(360, 720)

        if (!recordMutex.tryLock()) {
            throw ToolException("A clip recording is already in progress.")
        }
        try {
            val proj = synchronized(stateLock) { projection }
                ?: throw ToolException(NO_SESSION_MESSAGE)

            val (width, height, densityDpi) = computeCaptureGeometry(context, clampedShortEdge)
            val id = "clip_${clock()}_${UUID.randomUUID().toString().replace("-", "").take(6)}"
            clipsDir.mkdirs()
            val outFile = File(clipsDir, "$id.mp4")

            _isRecording.value = true
            var recorder: MediaRecorder? = null
            var virtualDisplay: VirtualDisplay? = null
            var started = false
            var stopped = false
            var startedAt = clock()
            var actualDurationMs = clampedDuration * 1000L

            try {
                recorder = withContext(Dispatchers.IO) {
                    createMediaRecorder(context).apply {
                        setVideoSource(MediaRecorder.VideoSource.SURFACE)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                        setVideoSize(width, height)
                        setVideoFrameRate(clampedFps)
                        setVideoEncodingBitRate(computeBitRate(width, height, clampedFps))
                        setOutputFile(outFile.absolutePath)
                        prepare()
                    }
                }

                virtualDisplay = try {
                    proj.createVirtualDisplay(
                        "ThumbsClipRecorder",
                        width,
                        height,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        recorder.surface,
                        null,
                        null,
                    )
                } catch (e: SecurityException) {
                    releaseProjection()
                    throw ToolException(
                        "Clip recording session expired or was revoked by Android (${e.message}). " +
                            "Ask the owner to open Thumbs and tap 'Enable clip recording' again.",
                    )
                } catch (e: IllegalStateException) {
                    releaseProjection()
                    throw ToolException(
                        "Clip recording session is no longer active (${e.message}). " +
                            "Ask the owner to open Thumbs and tap 'Enable clip recording' again.",
                    )
                }

                if (virtualDisplay == null) {
                    throw ToolException("Android could not create a virtual display for clip recording.")
                }

                withContext(Dispatchers.IO) { recorder.start() }
                started = true
                startedAt = clock()
                delay(clampedDuration * 1000L)
                actualDurationMs = (clock() - startedAt).coerceAtLeast(1000L)

                withContext(Dispatchers.IO) {
                    recorder.stop()
                }
                stopped = true
            } catch (e: CancellationException) {
                outFile.delete()
                throw e
            } catch (e: ToolException) {
                outFile.delete()
                throw e
            } catch (e: Exception) {
                outFile.delete()
                throw ToolException("Clip recording failed: ${e.message ?: e::class.java.simpleName}")
            } finally {
                withContext(Dispatchers.IO + NonCancellable) {
                    if (started && !stopped) {
                        runCatching { recorder?.stop() }
                    }
                    runCatching { recorder?.reset() }
                    runCatching { recorder?.release() }
                    runCatching { virtualDisplay?.release() }
                    if (!stopped) outFile.delete()
                }
                _isRecording.value = false
            }

            if (!outFile.isFile || outFile.length() == 0L) {
                outFile.delete()
                throw ToolException("Clip recording finished but produced an empty file.")
            }

            // Video-only MP4 so Thumbs never requests microphone / audio-capture permissions silently.
            val metadata = ClipMetadata(
                id = id,
                createdAt = startedAt,
                durationMs = actualDurationMs,
                width = width,
                height = height,
                fps = clampedFps,
                hasAudio = false,
                sizeBytes = outFile.length(),
            )
            synchronized(stateLock) {
                saveMetadata(metadata)
                trimOldestLocked()
                _clips.value = loadAllLocked()
            }
            return metadata
        } finally {
            recordMutex.unlock()
        }
    }

    private fun createMediaRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun computeCaptureGeometry(context: Context, maxShortEdge: Int): Triple<Int, Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val rawW = metrics.widthPixels.coerceAtLeast(360)
        val rawH = metrics.heightPixels.coerceAtLeast(360)
        val dpi = metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT)
        val shortEdge = minOf(rawW, rawH)
        val scale = if (shortEdge > maxShortEdge) maxShortEdge.toDouble() / shortEdge else 1.0
        // H.264 encoders require even dimensions
        val width = (((rawW * scale).toInt() / 2) * 2).coerceAtLeast(2)
        val height = (((rawH * scale).toInt() / 2) * 2).coerceAtLeast(2)
        return Triple(width, height, dpi)
    }

    private fun computeBitRate(width: Int, height: Int, fps: Int): Int =
        ((width.toLong() * height * fps) / 10L).coerceIn(1_500_000L, 6_000_000L).toInt()

    private fun saveMetadata(meta: ClipMetadata) {
        clipsDir.mkdirs()
        val target = File(clipsDir, "${meta.id}.json")
        val tmp = File(clipsDir, "${meta.id}.json.tmp")
        tmp.writeText(json.encodeToString(ClipMetadata.serializer(), meta))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun trimOldestLocked() {
        if (maxClips <= 0) return
        val all = loadAllLocked()
        if (all.size <= maxClips) return
        for (stale in all.drop(maxClips)) {
            File(clipsDir, "${stale.id}.mp4").delete()
            File(clipsDir, "${stale.id}.json").delete()
        }
    }

    private fun loadAll(): List<ClipMetadata> = synchronized(stateLock) { loadAllLocked() }

    private fun loadAllLocked(): List<ClipMetadata> {
        if (!clipsDir.isDirectory) return emptyList()
        val metaFiles = clipsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return metaFiles.mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            if (!SAFE_ID_REGEX.matches(id)) return@mapNotNull null
            val mp4 = File(clipsDir, "$id.mp4")
            if (!mp4.isFile) return@mapNotNull null
            runCatching {
                val decoded = json.decodeFromString(ClipMetadata.serializer(), f.readText())
                if (decoded.id == id) decoded else null
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    companion object {
        private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{4,64}$")
        private const val NO_SESSION_MESSAGE =
            "Clip recording session is not active on the phone. Ask the owner to open Thumbs and tap " +
                "'Enable clip recording' (Android requires an on-screen confirmation before video capture), " +
                "or use capture_burst / Share to Thumbs instead."
    }
}
