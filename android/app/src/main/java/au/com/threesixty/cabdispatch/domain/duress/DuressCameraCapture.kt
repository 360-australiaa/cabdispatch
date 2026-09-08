package au.com.threesixty.cabdispatch.domain.duress

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Real CameraX-backed duress cabin-camera still-frame capture (cabin-camera snapshot gallery
 * feature, 2026-08-27 — backend/dashboard already shipped, this is the Android side, see
 * `android/HANDOFF.md`'s "Duress cabin-camera snapshot gallery" entry for the full feature scope).
 * Owned/called by [au.com.threesixty.cabdispatch.domain.DuressController.runActivePhase] exactly
 * like [DuressAudioRecorder] — this class only knows how to start/stop a repeating capture loop
 * and hand back JPEG bytes, it has no idea what a duress event or its lifecycle is (same split
 * that class's own doc describes against [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider]).
 *
 * ### Front (cabin-facing) camera, not rear
 * This captures the DRIVER/cabin, not the road ahead — [CameraSelector.DEFAULT_FRONT_CAMERA],
 * deliberately not `DEFAULT_BACK_CAMERA`.
 *
 * ### Permission handling — same graceful-degradation pattern as [DuressAudioRecorder]
 * [start] checks [Manifest.permission.CAMERA] and returns `false` (a silent no-op, never a throw)
 * if it isn't granted — [DuressController] never crashes/blocks the duress state machine either
 * way. Does not itself poll for a later grant, same reasoning as [DuressAudioRecorder]'s doc: a
 * duress Active phase is one bounded lifecycle, not a process-lifetime subscription.
 *
 * ### No viewfinder, no local storage
 * Headless — no `PreviewView`, the driver never sees what's being captured (matches the
 * already-agreed "event-scoped only, no visible indication" design). Frames are captured directly
 * to an in-memory JPEG `ByteArray` via [ImageCapture.OnImageCapturedCallback] (never
 * `takePicture(OutputFileOptions, ...)` — nothing is ever written to device storage) and handed to
 * the caller to upload immediately; nothing persists locally, matching [DuressAudioRecorder]'s own
 * "no file cleanup needed" contrast except this class never had a file to begin with.
 *
 * ### Lifecycle binding
 * [ProcessCameraProvider.bindToLifecycle] requires a [androidx.lifecycle.LifecycleOwner] — this
 * app is single-activity/always-foreground-kiosk (see `MainActivity`'s own doc), so
 * [ProcessLifecycleOwner] is the correct binding target: there is no separate
 * Activity/Fragment lifecycle worth using instead here, and it means this class needs no
 * Activity/Fragment reference at all, only the application [Context] — mirrors
 * [DuressAudioRecorder] taking just a [Context].
 *
 * ### No 60s cap, unlike audio
 * [DuressAudioRecorder.MAX_RECORDING_DURATION_MS] is an explicit blueprint-mandated ring-buffer
 * simplification with no camera equivalent — [DuressController] runs this capture loop for as
 * long as the duress event stays open (stopping only on terminal status), since more frames over
 * a longer window is exactly the point of a snapshot gallery, unlike audio's fixed cap.
 */
class DuressCameraCapture(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Non-null only between a successful [start] and its matching [stop]. */
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    /**
     * Binds a headless front-camera [ImageCapture] use case and returns a [Flow] that emits a
     * freshly-captured JPEG [ByteArray] every [SNAPSHOT_INTERVAL_MS] for as long as it is
     * collected. Returns `null` (a no-op, never a throw) if:
     * - [Manifest.permission.CAMERA] isn't currently granted — the graceful-degradation case this
     *   class exists to handle, see class doc.
     * - a capture session is already bound ([cameraProvider] non-null) — [DuressController] never
     *   intentionally calls [start] twice for one Active phase, but this guards against it anyway.
     * - camera provider/use-case binding throws for any other reason (no front camera hardware,
     *   another app/process holding it exclusively, etc.) — real devices can fail here in ways
     *   this pass can't enumerate; caught and swallowed exactly like [DuressAudioRecorder]'s own
     *   `MediaRecorder` setup failure path.
     *
     * The returned [Flow] never throws for a single failed capture — [ImageCaptureException] on
     * one frame is logged-and-skipped (via `runCatching`-style swallow) so one bad frame doesn't
     * kill the whole loop; it completes only when the caller stops collecting (i.e. calls [stop],
     * which triggers [kotlinx.coroutines.flow.callbackFlow]'s `awaitClose`) or a fatal binding
     * error occurs.
     */
    suspend fun start(): Flow<ByteArray>? = withContext(Dispatchers.Main) {
        if (cameraProvider != null) return@withContext null
        if (!hasPermission()) return@withContext null

        val provider = runCatching { awaitCameraProvider() }.getOrNull()
            ?: return@withContext null

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val bound = runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(ProcessLifecycleOwner.get(), CameraSelector.DEFAULT_FRONT_CAMERA, capture)
        }
        if (bound.isFailure) {
            runCatching { provider.unbindAll() }
            return@withContext null
        }

        cameraProvider = provider
        imageCapture = capture

        repeatingCaptureFlow(capture)
    }

    /** Bridges CameraX's Guava `ListenableFuture<ProcessCameraProvider>` into a suspend call
     * without pulling in the `kotlinx-coroutines-guava` interop artifact — a plain
     * `addListener`/`Runnable` callback on the main-thread executor is all `.await()` would have
     * done here anyway, for one call site. */
    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { if (cont.isActive) cont.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /** A cabin-facing repeating capture loop — every [SNAPSHOT_INTERVAL_MS], takes one frame via
     * [captureOnce] and emits its JPEG bytes; a single failed/empty frame is skipped, not fatal. */
    private fun repeatingCaptureFlow(capture: ImageCapture): Flow<ByteArray> = flow {
        while (currentCoroutineContextIsActive()) {
            val bytes = runCatching { captureOnce(capture) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) emit(bytes)
            delay(SNAPSHOT_INTERVAL_MS)
        }
    }

    private suspend fun currentCoroutineContextIsActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    /** One in-memory capture — [ImageCapture.takePicture] with no [ImageCapture.OutputFileOptions]
     * (the in-memory [ImageProxy] path), converted to JPEG bytes and closed. */
    private suspend fun captureOnce(capture: ImageCapture): ByteArray = suspendCancellableCoroutine { cont ->
        capture.takePicture(
            ContextCompat.getMainExecutor(appContext),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = runCatching { image.toJpegByteArray() }.getOrNull() ?: ByteArray(0)
                    image.close()
                    if (cont.isActive) cont.resume(bytes)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            },
        )
    }

    /** Unbinds the camera use case and releases the provider — safe/idempotent to call
     * speculatively (mirrors [DuressAudioRecorder.stop]'s "no-op if nothing was recording"
     * contract), including when [start] was never called or already returned `null`. No file
     * cleanup needed — see class doc, nothing was ever written to disk. */
    suspend fun stop() = withContext(Dispatchers.Main) {
        val provider = cameraProvider
        cameraProvider = null
        imageCapture = null
        if (provider != null) runCatching { provider.unbindAll() }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Blueprint's "periodic JPEG still frames (every ~2-5s)" — 3000ms lands in the middle of
         * that range, matching the queued task's own suggestion. */
        const val SNAPSHOT_INTERVAL_MS = 3000L
    }
}

/** [ImageProxy] (JPEG format, since [ImageCapture]'s default output format is JPEG) -> raw JPEG
 * bytes via its single YUV/JPEG plane buffer. CameraX's in-memory capture path already hands back
 * JPEG-encoded planes for the default `ImageCapture` configuration (no `ImageAnalysis`/YUV
 * decode step needed) — this just copies the one plane's [java.nio.ByteBuffer] out before the
 * caller closes the [ImageProxy]. */
private fun ImageProxy.toJpegByteArray(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}
