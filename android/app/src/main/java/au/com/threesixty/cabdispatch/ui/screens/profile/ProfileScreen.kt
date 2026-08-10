package au.com.threesixty.cabdispatch.ui.screens.profile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.ChecklistItemDto
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.ui.screens.settings.SettingsScreen
import au.com.threesixty.cabdispatch.ui.theme.WheelColors
import java.io.ByteArrayOutputStream

private enum class ProfileTab(val label: String) { COMPLIANCE("Compliance"), SETTINGS("Settings") }

/**
 * Rows 20-21 - Profile (spec section 5: "Identity card, top-left ... tappable, opens Profile
 * (Compliance + Settings, demoted off the wheel since they're low-frequency)" / section 8: "Profile:
 * Compliance vault, Settings - Not yet designed").
 *
 * Two sections behind one entry point, per task instructions:
 *  - **Compliance** ([ProfileViewModel]/[ComplianceSection]): new, reads the real backend
 *    Compliance Vault dossier (`GET /v1/compliance/vehicles/{id}/dossier`) read-only.
 *  - **Settings** ([SettingsScreen]): the *existing* S6 screen, embedded verbatim - its
 *    [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel] is reused as-is per
 *    task instructions ("don't duplicate the underlying ViewModel logic"), just relocated behind
 *    this new tab instead of only being reachable via the standalone
 *    [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes.SETTINGS] route. That
 *    standalone route is deliberately left registered too (see `CabDispatchNavHost.kt`) - every
 *    other screen's small gear-icon affordance still targets it directly per the existing
 *    "S6 reachable from every screen" convention (`android/README.md`), so removing it would be a
 *    regression, not a cleanup.
 *
 * Reached from [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]'s
 * top-left identity card, per spec section 5 - that screen's `IdentityCard onClick` navigates to
 * [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes.PROFILE] (was a temporary
 * stand-in pointing at [CabDispatchRoutes.SETTINGS] before this screen existed; repointed as
 * part of this pass).
 *
 * **2026-08-10 driver-photo pass:** [IdentityHeader] gained a tap-to-capture/pick avatar photo
 * (see that composable's own doc) - both it and [ComplianceSection] now share one
 * [ProfileViewModel] instance (explicit `viewModel()` default param resolved once here and passed
 * down, not re-resolved independently per composable) so a photo captured here is reflected
 * immediately without a second network round trip.
 */
@Composable
fun ProfileScreen(
    navController: NavHostController,
    onFactoryReset: () -> Unit,
    viewModel: ProfileViewModel = viewModel(),
) {
    var tab by remember { mutableStateOf(ProfileTab.COMPLIANCE) }
    val session by SessionHolder.session.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(WheelColors.bg)) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹ Back",
                    color = WheelColors.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { navController.popBackStack() },
                )
                Text("Profile", color = WheelColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.widthIn(min = 48.dp))
            }
            Spacer(Modifier.height(20.dp))

            IdentityHeader(session, viewModel)
            Spacer(Modifier.height(20.dp))

            TabRow(selected = tab, onSelect = { tab = it })
        }

        when (tab) {
            ProfileTab.COMPLIANCE -> ComplianceSection(modifier = Modifier.padding(horizontal = 24.dp), viewModel = viewModel)
            ProfileTab.SETTINGS -> SettingsScreen(navController = navController, onFactoryReset = onFactoryReset)
        }
    }
}

/** Mirrors the prototype's `.identity` card (avatar initials + name + "driver ID . rego") -
 * see `docs/driver-dashboard-full-prototype.html`'s `#dashboard .identity` - reused here rather
 * than reinvented since Profile is opened directly from that same card (see this file's header
 * doc).
 *
 * **2026-08-10 driver-photo pass:** the avatar circle is now tappable-photo, not initials-only.
 * Two entry points below it - "Camera" ([ActivityResultContracts.TakePicturePreview], the
 * simpler of the two standard capture contracts: no `FileProvider`/manifest `<provider>` entry
 * needed since it hands back an in-memory [Bitmap] rather than writing to a content [Uri] this
 * app would have to own - and "Gallery" ([ActivityResultContracts.GetContent], needs no runtime
 * storage permission either, since it launches the system document/photo picker out-of-process).
 * Neither existing pattern in this module ([QrScanner][au.com.threesixty.cabdispatch.domain.QrScanner]
 * is a stub with no real camera wiring; nothing else captures/picks images) had a real
 * capture-contract precedent to mirror, so this is the first real
 * `rememberLauncherForActivityResult` call site in the module - both contracts are standard,
 * well-documented `androidx.activity` surface, low risk relative to this module's other
 * hardware-adjacent code (Mapbox/MediaRecorder).
 *
 * Camera capture needs a runtime `CAMERA` grant before launching - unlike
 * [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider]'s poll-and-degrade
 * pattern (a passive background feed with no single user tap to hang a permission prompt off
 * of), this is a direct one-tap user action, so requesting inline
 * ([ActivityResultContracts.RequestPermission]) is the natural fit and is the first real runtime
 * permission *request* flow anywhere in this module (every existing
 * `ContextCompat.checkSelfPermission` hit before this pass only checked, per HANDOFF.md's own
 * standing gap list) - denied gracefully falls back to an inline hint, never a crash, matching
 * this module's "never crash on missing permission" convention throughout. `AndroidManifest.xml`
 * already declares `CAMERA` (added for the still-stubbed QR scanner), so no manifest change was
 * needed for this pass. */
@Composable
private fun IdentityHeader(session: DriverSession?, viewModel: ProfileViewModel = viewModel()) {
    val context = LocalContext.current
    val photoState by viewModel.photoState.collectAsState()
    val isUploadingPhoto by viewModel.isUploadingPhoto.collectAsState()
    val photoUploadError by viewModel.photoUploadError.collectAsState()
    var cameraPermissionDenied by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) viewModel.uploadPhoto(bitmap.toJpegBytes())
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraPermissionDenied = false
            cameraLauncher.launch(null)
        } else {
            cameraPermissionDenied = true
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        // Empty ByteArray on a read failure deliberately reuses ProfileViewModel.uploadPhoto's own
        // "bytes.isEmpty()" guard rather than a second error-plumbing path here - see that
        // function's doc.
        viewModel.uploadPhoto(bytes ?: ByteArray(0))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(WheelColors.gold)
                .clickable {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        cameraPermissionDenied = false
                        cameraLauncher.launch(null)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (val p = photoState) {
                is ProfilePhotoUiState.Loaded -> Image(
                    bitmap = p.bitmap.asImageBitmap(),
                    contentDescription = "Profile photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
                else -> {
                    val initials = session?.driverName
                        ?.split(" ")
                        ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
                        ?.take(2)
                        ?.joinToString("")
                        ?: "?"
                    Text(initials, color = WheelColors.surfaceRaised, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            if (isUploadingPhoto || photoState is ProfilePhotoUiState.Loading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(WheelColors.surfaceSunken.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = WheelColors.gold, strokeWidth = 2.dp)
                }
            }
        }
        Spacer(Modifier.widthIn(min = 12.dp))
        Column {
            Text(
                session?.driverName ?: "Not signed in",
                color = WheelColors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (session != null) "${session.driverId} · ${session.vehicleId}" else "-",
                color = WheelColors.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Camera",
                    color = WheelColors.gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            cameraPermissionDenied = false
                            cameraLauncher.launch(null)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                Text(
                    "Gallery",
                    color = WheelColors.gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { galleryLauncher.launch("image/*") },
                )
            }
        }
    }
    if (cameraPermissionDenied) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Camera permission was not granted - use Gallery instead, or allow Camera in Android Settings.",
            color = WheelColors.textMuted,
            fontSize = 11.sp,
        )
    }
    photoUploadError?.let { message ->
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = WheelColors.duress, fontSize = 11.sp, modifier = Modifier.widthIn(max = 260.dp))
            Spacer(Modifier.widthIn(min = 8.dp))
            Text(
                "Dismiss",
                color = WheelColors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { viewModel.dismissPhotoError() },
            )
        }
    }
}

/** JPEG-encodes a captured preview [Bitmap] for upload - [ProfileViewModel.uploadPhoto] takes raw
 * bytes rather than a [Bitmap] so that class stays UI-toolkit-agnostic (see its own doc); this is
 * the one Bitmap-to-bytes conversion call site, kept local to this file rather than a shared
 * util since nothing else in this module encodes an in-memory Bitmap today. Quality 85 is a plain
 * reasonable default, not tuned/measured against this app's actual upload-size constraints (the
 * backend's own doc does not name a max size) - flagged in HANDOFF.md as unverified alongside
 * everything else this pass could not run through a real device/compiler. */
private fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return stream.toByteArray()
}

@Composable
private fun TabRow(selected: ProfileTab, onSelect: (ProfileTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileTab.entries.forEach { t ->
            val isSelected = t == selected
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) WheelColors.surfaceSunken else WheelColors.surfaceRaised,
                        RoundedCornerShape(10.dp),
                    )
                    .border(
                        1.dp,
                        if (isSelected) WheelColors.gold else WheelColors.border,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(t) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    t.label,
                    color = if (isSelected) WheelColors.textPrimary else WheelColors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Row 20 content - read-only cl.14 checklist summary. Falls back to a plain "not uploaded" list
 * of the known doc types if the dossier call fails/has no session (spec's own explicitly-sanctioned
 * fallback: "if nothing exists yet, a simple placeholder list of compliance doc types with 'not
 * uploaded' status is acceptable") rather than a bare error screen, since a driver checking their
 * own compliance standing offline/mid-shift is a real use case, not just a happy-path one.
 */
@Composable
private fun ComplianceSection(modifier: Modifier = Modifier, viewModel: ProfileViewModel = viewModel()) {
    val state by viewModel.complianceState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val s = state) {
            is ComplianceUiState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = WheelColors.gold)
                }
            }
            is ComplianceUiState.Error -> {
                item {
                    Text(
                        s.message,
                        color = WheelColors.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                items(FALLBACK_DOC_TYPES) { docType -> ComplianceRow(label = docType, satisfied = null) }
            }
            is ComplianceUiState.Loaded -> {
                item {
                    OverallBanner(compliant = s.dossier.overallCompliant)
                }
                items(s.dossier.items) { checklistItem: ChecklistItemDto ->
                    ComplianceRow(label = checklistItem.label, satisfied = checklistItem.satisfied)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun OverallBanner(compliant: Boolean) {
    val color = if (compliant) WheelColors.available else WheelColors.waiting
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.widthIn(min = 10.dp))
        Text(
            if (compliant) "Vehicle compliant - all documents on file" else "Missing required compliance documents",
            color = WheelColors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ComplianceRow(label: String, satisfied: Boolean?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColors.surfaceRaised.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(1.dp, WheelColors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = WheelColors.textPrimary, fontSize = 14.sp)
        val (statusLabel, statusColor) = when (satisfied) {
            true -> "On file" to WheelColors.available
            false -> "Not uploaded" to WheelColors.waiting
            null -> "Not uploaded" to WheelColors.textMuted
        }
        Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Mirrors backend `app.models.compliance.VALID_DOC_TYPES` labels - used only as the read-only
 * placeholder fallback when the dossier call fails/there's no bound session (see
 * [ComplianceSection]'s doc), NOT as a substitute for the real dossier read when it succeeds. */
private val FALLBACK_DOC_TYPES = listOf(
    "Calibration record",
    "Mounting photo",
    "Accuracy test",
    "Cl.14 checklist",
    "Camera register",
    "Duress alarm register",
    "Tracking register",
)
