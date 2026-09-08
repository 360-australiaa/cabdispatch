package au.com.threesixty.cabdispatch.domain

import android.app.Activity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Camera-based vehicle QR pairing, per spec B5 S1 ("QR vehicle pairing"). Kept behind an
 * interface so S1 works fully without camera hardware — the UI must always also offer the
 * manual-entry text field fallback described in the same spec line, never gate vehicle binding
 * on this succeeding.
 */
interface QrScanner {
    /**
     * Launches the platform scan UI/flow and suspends until a code is read or the user cancels.
     * Returns null on cancel/failure/unavailable hardware — never throws for those conditions;
     * callers fall back to manual entry instead. [activity] is required because the scan UI is a
     * real Activity-hosted flow (the ML Kit "Google code scanner" module) — this app is
     * single-activity (see MainActivity's doc), so callers always have exactly one to pass.
     */
    suspend fun scan(activity: Activity): String?
}

/** Debug/no-camera-hardware fallback — always returns null so the manual-entry path is the only
 * usable one. Kept for completeness/tests; [RealQrScanner] is what [AppContainer.qrScanner]
 * actually constructs now. */
class StubQrScanner : QrScanner {
    override suspend fun scan(activity: Activity): String? = null
}

/**
 * Real implementation (2026-08-28, replaces the former always-null stub). Uses the ML Kit "Google
 * code scanner" (`GmsBarcodeScanning`) rather than a hand-built CameraX `PreviewView` + analyzer:
 * it ships its own full-screen scan UI and handles the CAMERA runtime permission prompt itself, so
 * no viewfinder Composable or permission-request plumbing is needed here — [QrScanner.scan] stays
 * a single suspend call, matching the interface every caller (just
 * [au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.scanQr]) already
 * expects.
 *
 * On a device where the on-device scanning module isn't yet installed, the client transparently
 * triggers a Play Services module download on first use (a few hundred KB, not the full ML Kit
 * SDK) — `startScan()` surrenders control to that flow automatically, no separate module-install
 * pre-check needed for this app's one-shot "scan on demand" use.
 *
 * **Verified live on a physical device** (2026-08-28): tapping the QR panel launches the real
 * Google-branded full-screen scan UI ("Scanned by Google on behalf of Cab Dispatch") with a live
 * camera feed and working flash toggle; backing out cancels cleanly (`addOnCanceledListener`,
 * confirmed via logcat — no exception, no crash) and returns to this screen with the manual-entry
 * fallback fully intact. The one leg not exercised in that pass: actually decoding a real QR code
 * end-to-end into [vehicleIdInput] — no second display was available in that test environment to
 * present one to the tablet's camera. `barcode.rawValue` → `vehicleIdInput` is a single trivial
 * assignment on Google's own well-established, independently-tested decode path, not new risk this
 * class introduces.
 */
class RealQrScanner : QrScanner {
    override suspend fun scan(activity: Activity): String? {
        val scanner: GmsBarcodeScanner = GmsBarcodeScanning.getClient(activity)
        return suspendCancellableCoroutine { cont ->
            scanner.startScan()
                .addOnSuccessListener { barcode: Barcode ->
                    cont.resume(barcode.rawValue)
                }
                .addOnCanceledListener {
                    cont.resume(null)
                }
                .addOnFailureListener {
                    // Camera permission denied, no camera hardware, module install failed, etc. —
                    // fail closed to null exactly like every other condition this interface
                    // documents, never propagate the exception (see class doc: "never throws").
                    cont.resume(null)
                }
        }
    }
}
