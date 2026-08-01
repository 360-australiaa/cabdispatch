package au.com.threesixty.cabdispatch.domain

/**
 * Camera-based vehicle QR pairing, per spec B5 S1 ("QR vehicle pairing").
 * Kept behind an interface so S1 works fully without camera hardware — the
 * UI must always also offer the manual-entry text field fallback described
 * in the same spec line, never gate vehicle binding on this succeeding.
 */
interface QrScanner {
    /**
     * Launches the platform scan UI/flow and suspends until a code is read
     * or the user cancels. Returns null on cancel/failure/unavailable
     * hardware — never throws for those conditions; callers fall back to
     * manual entry instead.
     */
    suspend fun scan(): String?
}

/**
 * TODO(camera sibling agent): replace with a CameraX + ML Kit
 * barcode-scanning implementation. Needs `android.permission.CAMERA`
 * (already declared in the manifest) plus a real device/emulator with camera
 * support to verify, which this build environment does not have. Always
 * returns null so the manual-entry fallback is the only usable path until
 * that lands.
 */
class StubQrScanner : QrScanner {
    override suspend fun scan(): String? = null
}
