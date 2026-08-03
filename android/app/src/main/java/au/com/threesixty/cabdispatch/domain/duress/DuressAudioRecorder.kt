package au.com.threesixty.cabdispatch.domain.duress

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real `MediaRecorder`-backed duress audio capture (Blueprint §4.3/§8.3: "optional audio
 * recording ... starting the moment a duress alarm goes Active"). Owned/called by
 * [au.com.threesixty.cabdispatch.domain.DuressController.runActivePhase] — this class only
 * knows how to start/stop a local recording file, it has no idea what a duress event or its
 * lifecycle is (mirrors [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider]'s
 * split: that class owns the raw GPS read, [au.com.threesixty.cabdispatch.domain.location.RegionResolver]
 * / the fare engine own what to do with it — same shape here).
 *
 * ### Permission handling — same graceful-degradation pattern as [RealLocationProvider]
 * [start] checks [Manifest.permission.RECORD_AUDIO] via [ContextCompat.checkSelfPermission] and
 * simply returns `false` (a silent no-op) if it isn't granted — it never throws, and
 * [au.com.threesixty.cabdispatch.domain.DuressController] never crashes/blocks the duress
 * state machine either way, exactly like [RealLocationProvider] leaving [locationFix]
 * `null`/[speedKmh] `0.0` while ungranted. Unlike [RealLocationProvider] this class does not
 * itself poll for a later grant — the duress "Active" phase this is scoped to is a single
 * ~10s-to-a-few-minutes lifecycle (see [au.com.threesixty.cabdispatch.domain.DuressController]'s
 * `CANCEL_WINDOW_SECONDS`/`TERMINAL_STATUSES`), not a process-lifetime subscription, so there is
 * no meaningful "grant happened mid-cycle" case worth polling for — the next duress cycle's
 * [start] call re-checks the permission fresh.
 *
 * ### Simplification, documented per this pass's brief (NOT a true rolling circular buffer)
 * Blueprint §4.3/§8.3 calls for "a 60-second circular buffer" — this implementation is the
 * simpler of the two options the brief explicitly sanctioned instead: record into a single file
 * for up to [MAX_RECORDING_DURATION_MS] (~60s) and then stop, rather than a real ring buffer that
 * keeps only the trailing 60 seconds of a longer recording. Chosen because a genuine circular
 * buffer needs either (a) chunked recording into rolling short segments stitched together, or
 * (b) a raw PCM ring buffer + a manual encoder pass — both meaningfully more moving parts than a
 * capped single `MediaRecorder` session, for a device this pass's author has never been able to
 * compile let alone run. [au.com.threesixty.cabdispatch.domain.DuressController] enforces the
 * ~60s cap by calling [stop] once [MAX_RECORDING_DURATION_MS] has elapsed since [start] returned
 * `true` — see that class's `runActivePhase` for exactly where.
 *
 * ### File format
 * MPEG-4 container + AAC encoder (`.m4a`) — matches the backend's own doc-comment example path
 * (`app/services/duress.py#save_duress_audio`: `"uploads/{tenant_id}/duress/{event_id}/audio_{event_id}.m4a"`)
 * and is a filename extension `MediaRecorder.OutputFormat.MPEG_4`/`AudioEncoder.AAC` genuinely
 * produces — not a guess.
 *
 * Not thread-safe beyond what its own `withContext(Dispatchers.IO)` calls provide — callers
 * ([DuressController]) only ever drive one duress cycle's [start]/[stop] pair at a time off a
 * single coroutine, same as every other call into that class.
 */
class DuressAudioRecorder(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Non-null only between a successful [start] and its matching [stop]. */
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /**
     * Starts recording to a local file named after [eventId] (see class doc re: filename/backend
     * path convention). Returns `false` (a no-op, never a throw) if:
     * - [Manifest.permission.RECORD_AUDIO] isn't currently granted — the graceful-degradation
     *   case this class exists to handle, see class doc.
     * - a recording is already in progress ([recorder] non-null) — [DuressController] never
     *   intentionally calls [start] twice for one Active phase, but this guards against it
     *   anyway rather than leaking/overwriting a `MediaRecorder` instance.
     * - the underlying `MediaRecorder` setup/`prepare()`/`start()` throws for any other reason
     *   (no mic hardware, another app holding it exclusively, low storage, etc.) — real devices
     *   can fail here in ways this sandbox can't enumerate; caught and swallowed exactly like
     *   [RealLocationProvider.rawLocationUpdates]' `requestLocationUpdates` registration failure.
     */
    suspend fun start(eventId: String): Boolean = withContext(Dispatchers.IO) {
        if (recorder != null) return@withContext false
        if (!hasPermission()) return@withContext false

        val dir = File(appContext.filesDir, AUDIO_SUBDIR).apply { mkdirs() }
        val file = File(dir, "audio_$eventId.m4a")

        val attempt = runCatching {
            newMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }

        val mr = attempt.getOrNull()
        if (mr == null) {
            // prepare()/start() (or construction itself) threw — nothing further to tear down;
            // whatever partially-constructed MediaRecorder existed is left for GC rather than
            // risking a second exception calling release() on an object that never finished
            // preparing.
            return@withContext false
        }

        recorder = mr
        outputFile = file
        true
    }

    /**
     * Stops the in-progress recording (if any) and releases the `MediaRecorder`. Returns the
     * recorded [File] only if one exists and has non-zero length (matches the backend's `400 if
     * file empty` contract — no point uploading a zero-byte file just to have the server reject
     * it); returns `null` otherwise, including when nothing was recording (safe/idempotent to
     * call speculatively, which [DuressController] does on both the "60s cap elapsed" and
     * "event reached terminal status" paths — whichever happens first wins, the other becomes a
     * no-op).
     */
    suspend fun stop(): File? = withContext(Dispatchers.IO) {
        val mr = recorder ?: return@withContext null
        recorder = null
        val file = outputFile
        outputFile = null

        // MediaRecorder.stop() is documented to throw RuntimeException when no valid data was
        // ever written (e.g. stopped within the same instant as start()) — a real, known
        // platform quirk, not a bug in this class; swallowed like every other best-effort call
        // here, the file-length check below is what actually decides whether there's anything
        // worth uploading.
        runCatching { mr.stop() }
        runCatching { mr.release() }

        file?.takeIf { it.exists() && it.length() > 0L }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** `MediaRecorder(Context)` (the non-deprecated constructor) needs API 31+; this project's
     * minSdk is 29 (see `RealLocationProvider`'s doc re: the same Ed25519/API-level constraint
     * elsewhere in this module), so the plain no-arg constructor is still required below that. */
    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            MediaRecorder()
        }

    companion object {
        /** Blueprint §4.3/§8.3's ~60-second cap — see class doc for the "capped single file, not
         * a true rolling buffer" simplification this constant enforces the boundary of. */
        const val MAX_RECORDING_DURATION_MS = 60_000L
        private const val AUDIO_SUBDIR = "duress_audio"
    }
}
