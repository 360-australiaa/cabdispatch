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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ComplianceExpiryItemDto
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 30 · Profile — reskinned onto [CaptainPalette]/[au.com.threesixty.cabdispatch.ui.theme.CaptainWidgets]
 * (2026-08-29 purple migration pass). [ProfileViewModel] is kept ENTIRELY: photo capture/upload
 * (TakePicturePreview + GetContent launchers, CAMERA runtime-permission request), the read-only
 * compliance dossier, and error dismiss — this pass only replaces presentation, off the old
 * yellow/black `Deck` tokens.
 *
 * Layout: title top-left, an identity card (140dp avatar, "Tap to update photo", label/value
 * attribute rows) and a compliance column filling the rest, outline "Dashboard" pinned bottom-left.
 * ZERO vertical scroll — the dossier checklist renders as a 2-wide grid of compact cards so all
 * seven cl.14 items + the expiry warnings resolve on canvas.
 *
 * NEW real data: the amber compliance-expiry warning cards ("expiring in 54 days — renew soon")
 * load the real `GET /v1/fleet/compliance-expiry` feed ([AppContainer.apiService]
 * `complianceExpiry()`) via a small screen-local loader — editing [ProfileViewModel] is off-limits
 * for this pass — and the section simply hides on failure/empty.
 *
 * Honesty deviations, flagged: the status strip is dashboard-owned state and is omitted.
 * [onFactoryReset] stays in the signature (the NavHost contract) even though the embedded
 * Settings host lives on its own route.
 *
 * Phase H (2026-09-03) additions:
 * - **Identity card**: the VEHICLE row now shows "GHP-1 · Toyota Camry Hybrid" instead of just the
 *   rego, once [ProfileViewModel.vehicleDetail] resolves ([au.com.threesixty.cabdispatch.data.remote.VehicleDto.make]/`.model`,
 *   threaded from the backend's `Vehicle.make`/`.model`). Same gap `DeckHomeScreen.kt`'s own header
 *   comment flags as "no vehicle make/model field exists ... backend-requirements candidate in
 *   DASHBOARD_REDESIGN_2026.md" — that backend field now exists (this session's earlier fleet
 *   pass), so this card can honestly show it; falls back to the bare rego, same as before, when
 *   make/model are unset or the fetch fails. PHONE/MEMBER SINCE rows read [ProfileViewModel.userDetail]
 *   (`GET /v1/auth/me`) and are omitted entirely (never a placeholder) when that value is `null`.
 * - **No rating row**: checked this session — there is no rating concept anywhere in the backend
 *   (no column, no endpoint, no schema field). Per this codebase's zero-fake-affordance rule, no
 *   placeholder number is added; the row simply doesn't exist.
 * - **No new EDIT PROFILE button**: `PATCH /v1/users/{id}` (name/phone/email/etc.) is
 *   owner/admin-only server-side (`backend/app/api/v1/users.py::update_user`) — a driver cannot
 *   legitimately self-edit those fields today. The one field a driver CAN legitimately self-edit,
 *   their own photo (`POST /v1/users/{id}/photo`, self-or-staff gated), already has a real, working
 *   affordance — the avatar tap / "Tap to update photo" row in [IdentityCard] below — so no second,
 *   redundant button is added for it.
 * - **Documents tab**: a new [ProfileTab] pill switch (PROFILE | DOCUMENTS) now sits beside the
 *   title. DOCUMENTS shows real Licence/Registration/Insurance rows
 *   ([DocumentsPane]) with a genuine Verified/Expiring soon/Expired status computed from
 *   [au.com.threesixty.cabdispatch.data.remote.UserDto.driverLicenseExpiry]/
 *   [au.com.threesixty.cabdispatch.data.remote.VehicleDto.registrationExpiry]/`.insuranceExpiry` —
 *   read-only, no upload: `POST /v1/compliance/documents` (the only upload mechanism that exists)
 *   is staff-role-gated (owner/admin/dispatcher) for every doc type, existing or new, so wiring an
 *   upload button for a driver account would either silently 403 or need a real RBAC change this
 *   Android-scoped pass isn't positioned to make unprompted — see [DocumentsPane]'s own doc.
 *   "Police check" is omitted entirely, per task instructions — no backend field exists anywhere.
 */
@Composable
fun ProfileScreen(
    navController: NavHostController,
    onFactoryReset: () -> Unit,
    viewModel: ProfileViewModel = viewModel(),
) {
    val session by SessionHolder.session.collectAsState()
    var activeTab by remember { mutableStateOf(ProfileTab.PROFILE) }

    // Screen-local compliance-expiry loader (see class doc): null = failed/hidden, empty = loaded
    // none. Real network read, degrades to hiding the cards.
    var expiryItems by remember { mutableStateOf<List<ComplianceExpiryItemDto>>(emptyList()) }
    LaunchedEffect(Unit) {
        expiryItems = runCatching { AppContainer.apiService.complianceExpiry() }
            .getOrNull()?.items ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Driver profile", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileTabPill("Profile", selected = activeTab == ProfileTab.PROFILE) { activeTab = ProfileTab.PROFILE }
                ProfileTabPill("Documents", selected = activeTab == ProfileTab.DOCUMENTS) { activeTab = ProfileTab.DOCUMENTS }
            }
        }
        Spacer(Modifier.height(20.dp))

        when (activeTab) {
            ProfileTab.PROFILE -> Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                IdentityCard(session, viewModel)
                ComplianceColumn(viewModel = viewModel, expiryItems = expiryItems, modifier = Modifier.weight(1f))
            }
            ProfileTab.DOCUMENTS -> DocumentsPane(viewModel = viewModel, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            CaptainButton(text = "Dashboard", outline = true, modifier = Modifier.width(220.dp)) {
                navController.popBackStack()
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(text = "Settings & diagnostics", outline = true, modifier = Modifier.width(300.dp)) {
                navController.navigate(CabDispatchRoutes.SETTINGS)
            }
        }
    }
}

/** The 400dp identity card — avatar + photo actions + attribute rows. All photo capture/upload
 * logic below is byte-for-byte the pre-port behaviour, only restyled. */
@Composable
private fun IdentityCard(session: DriverSession?, viewModel: ProfileViewModel) {
    val context = LocalContext.current
    val photoState by viewModel.photoState.collectAsState()
    val isUploadingPhoto by viewModel.isUploadingPhoto.collectAsState()
    val photoUploadError by viewModel.photoUploadError.collectAsState()
    val vehicleDetail by viewModel.vehicleDetail.collectAsState()
    val userDetail by viewModel.userDetail.collectAsState()
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
        // "bytes.isEmpty()" guard rather than a second error-plumbing path here.
        viewModel.uploadPhoto(bytes ?: ByteArray(0))
    }

    fun launchCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraPermissionDenied = false
            cameraLauncher.launch(null)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    CaptainPanel(
        modifier = Modifier.width(400.dp).fillMaxHeight(),
        cornerRadiusDp = 24,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(CaptainPalette.raised)
                    .border(2.dp, CaptainPalette.panelBorder, CircleShape)
                    .clickable { launchCamera() },
                contentAlignment = Alignment.Center,
            ) {
                when (val p = photoState) {
                    is ProfilePhotoUiState.Loaded -> Image(
                        bitmap = p.bitmap.asImageBitmap(),
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                    // No real photo yet — initials placeholder, same fallback DriverAvatar uses
                    // elsewhere, rather than a stock/generic image standing in for a real person.
                    else -> {
                        val initials = session?.driverName
                            ?.split(" ")?.mapNotNull { it.firstOrNull()?.uppercase() }?.take(2)?.joinToString("")
                            ?: "—"
                        Text(initials, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, color = CaptainPalette.textPrimary)
                    }
                }
                if (isUploadingPhoto || photoState is ProfilePhotoUiState.Loading) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = CaptainPalette.accent, strokeWidth = 2.dp)
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { launchCamera() },
            ) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(18.dp))
                Text("Tap to update photo", fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.accent)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { galleryLauncher.launch("image/*") },
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = CaptainPalette.primary, modifier = Modifier.size(16.dp))
                Text("Choose from gallery", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CaptainPalette.primary)
            }
            Text(
                "Used by dispatch & duress monitoring",
                fontFamily = InterFamily,
                fontSize = 12.sp,
                color = CaptainPalette.textMuted,
            )

            if (cameraPermissionDenied) {
                Text(
                    "Camera permission was not granted — use the gallery instead, or allow Camera in Android Settings.",
                    fontFamily = InterFamily,
                    fontSize = 11.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            photoUploadError?.let { message ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, fontFamily = InterFamily, fontSize = 11.sp, color = CaptainPalette.danger)
                    Text(
                        "Dismiss",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = CaptainPalette.textSecondary,
                        modifier = Modifier.clickable { viewModel.dismissPhotoError() },
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IdentityAttribute("NAME", session?.driverName ?: "Not signed in")
                IdentityAttribute("DRIVER ID", session?.driverId ?: "—")
                // "GHP-1 · Toyota Camry Hybrid" once vehicleDetail resolves real make/model —
                // falls back to the bare rego (pre-Phase-H behaviour) when either is unset/unknown.
                // See this file's class doc for the DASHBOARD_REDESIGN_2026.md gap this closes.
                session?.vehicleId?.let { rego ->
                    val makeModel = vehicleDetail
                        ?.let { v -> listOfNotNull(v.make, v.model).joinToString(" ") }
                        ?.takeIf { it.isNotBlank() }
                    IdentityAttribute("VEHICLE", if (makeModel != null) "$rego · $makeModel" else rego)
                }
                // Real fields from GET /v1/auth/me — omitted entirely (never a placeholder/dash
                // row) when userDetail hasn't loaded yet or the driver has no phone on file.
                userDetail?.phone?.takeIf { it.isNotBlank() }?.let { IdentityAttribute("PHONE", it) }
                userDetail?.createdAt?.let { formatMemberSince(it) }?.let { IdentityAttribute("MEMBER SINCE", it) }
            }
        }
    }
}

@Composable
private fun IdentityAttribute(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = CaptainPalette.textMuted)
        Text(value, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
    }
}

/**
 * The compliance column: expiry warning cards (real `GET /v1/fleet/compliance-expiry` rows, amber
 * when near, red when expired) above the real cl.14 dossier checklist ([ProfileViewModel.complianceState])
 * rendered as a 2-wide grid of compact cards.
 */
@Composable
private fun ComplianceColumn(
    viewModel: ProfileViewModel,
    expiryItems: List<ComplianceExpiryItemDto>,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.complianceState.collectAsState()

    Column(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "COMPLIANCE DOCUMENTS — auto-tracked, dispatch blocked when expired",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = CaptainPalette.textMuted,
        )

        // Real expiry-feed warning cards (capped to 3 so the no-scroll canvas always resolves).
        expiryItems.take(3).forEach { item -> ExpiryCard(item) }

        when (val s = state) {
            is ComplianceUiState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CaptainPalette.accent)
            }
            is ComplianceUiState.Error -> {
                Text(s.message, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textSecondary)
                ComplianceGrid(FALLBACK_DOC_TYPES.map { it to null })
            }
            is ComplianceUiState.Loaded -> {
                OverallBanner(compliant = s.dossier.overallCompliant)
                ComplianceGrid(s.dossier.items.map { it.label to it.satisfied })
            }
        }
    }
}

/** One real `ComplianceExpiryItem` row — the amber "expiring in 54 days" card. */
@Composable
private fun ExpiryCard(item: ComplianceExpiryItemDto) {
    val expired = item.daysRemaining < 0 || item.status.equals("expired", ignoreCase = true)
    val accent = if (expired) CaptainPalette.danger else CaptainPalette.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CaptainPalette.panel)
            .border(1.5.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
            Text(
                if (expired) {
                    "${item.field} expired ${item.expiryDate}"
                } else {
                    "${item.field} expiring in ${item.daysRemaining} days — renew soon"
                },
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = accent,
            )
        }
        StatusBadge(icon = if (expired) Icons.Rounded.Cancel else Icons.Rounded.Warning, color = accent)
    }
}

@Composable
private fun OverallBanner(compliant: Boolean) {
    val color = if (compliant) CaptainPalette.success else CaptainPalette.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            if (compliant) "Vehicle compliant — all documents on file" else "Missing required compliance documents",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}

/** 2-wide grid of compact dossier rows — `satisfied` null renders the neutral "—" fallback. */
@Composable
private fun ComplianceGrid(items: List<Pair<String, Boolean?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { (label, satisfied) -> ComplianceCard(label, satisfied, modifier = Modifier.weight(1f)) }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ComplianceCard(label: String, satisfied: Boolean?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        when (satisfied) {
            true -> StatusBadge(icon = Icons.Rounded.CheckCircle, color = CaptainPalette.success)
            false -> StatusBadge(icon = Icons.Rounded.Warning, color = CaptainPalette.warning)
            null -> Text("—", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = CaptainPalette.textMuted)
        }
    }
}

@Composable
private fun StatusBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
    }
}

// ============================================================================
// Documents tab (Phase H, 2026-09-03)
// ============================================================================

private enum class ProfileTab { PROFILE, DOCUMENTS }

/** Small pill switch mirroring `ui/screens/vouchers/VouchersPaneContent.kt`'s `VoucherTabPill`
 * styling (no count badge here — just two states). */
@Composable
private fun ProfileTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CaptainPalette.primary else CaptainPalette.raised
    val textColor = if (selected) CaptainPalette.textPrimary else CaptainPalette.textSecondary
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.primary else CaptainPalette.panelBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = textColor, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/**
 * Real, read-only Licence/Registration/Insurance status rows — deliberately NOT the mockup's
 * upload/view Documents tab. Sourced from real expiry-date fields already on the backend
 * (`User.driver_license_expiry` via [ProfileViewModel.userDetail]'s `GET /v1/auth/me`;
 * `Vehicle.registration_expiry`/`.insurance_expiry` via [ProfileViewModel.vehicleDetail]'s
 * `GET /v1/fleet/vehicles`), status computed client-side by [documentStatusFor] using the same
 * 30-day warning threshold `backend/app/core/config.py`'s `COMPLIANCE_EXPIRY_WARNING_DAYS` default
 * uses (not read live — a plain client-side mirror of that constant, since there's no endpoint
 * that exposes the threshold itself).
 *
 * "Police Check" is intentionally absent — no backend field/doc-type exists anywhere for it, and
 * inventing one is a product decision outside this task's scope, not something to build unprompted.
 *
 * No upload/view affordance: the only real upload mechanism, `POST /v1/compliance/documents`
 * (`backend/app/api/v1/compliance.py`), is gated to `owner`/`admin`/`dispatcher` for every
 * `doc_type` — existing Cl.14 evidence types AND any new license/rego/insurance type this pass
 * could add to `VALID_DOC_TYPES`. Extending that tuple alone would not make upload real for a
 * driver account; it would still 403. Loosening that RBAC gate is a real product/security decision
 * (who may attest a driver's own licence is genuine?) outside this Android-focused pass's scope, so
 * this tab stays an honest read-only status list rather than a button that would either silently
 * fail or need an unplanned backend RBAC change.
 */
@Composable
private fun DocumentsPane(viewModel: ProfileViewModel, modifier: Modifier = Modifier) {
    val userDetail by viewModel.userDetail.collectAsState()
    val vehicleDetail by viewModel.vehicleDetail.collectAsState()

    Column(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "REAL DOCUMENT STATUS — from the licence/registration/insurance expiry dates on file. " +
                "Upload isn't available from this device (compliance-document upload is a staff-only " +
                "action server-side today).",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = CaptainPalette.textMuted,
        )
        DocumentStatusRow("DRIVER LICENCE", userDetail?.driverLicenseExpiry)
        DocumentStatusRow("VEHICLE REGISTRATION", vehicleDetail?.registrationExpiry)
        DocumentStatusRow("VEHICLE INSURANCE", vehicleDetail?.insuranceExpiry)
        Spacer(Modifier.weight(1f))
    }
}

private enum class DocumentStatus { VERIFIED, EXPIRING_SOON, EXPIRED, UNKNOWN }

/** Mirrors `app.services.compliance_expiry._status_for`'s expired/expiring_soon/(not-near) logic,
 * client-side, plus an explicit UNKNOWN for a `null`/unparseable date — that service never treats
 * a missing date as expired (fail-open, see its own module doc) and neither does this. */
private fun documentStatusFor(expiryIso: String?, warningDays: Long = 30): DocumentStatus {
    val expiry = expiryIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return DocumentStatus.UNKNOWN
    val today = LocalDate.now()
    return when {
        expiry.isBefore(today) -> DocumentStatus.EXPIRED
        ChronoUnit.DAYS.between(today, expiry) <= warningDays -> DocumentStatus.EXPIRING_SOON
        else -> DocumentStatus.VERIFIED
    }
}

private fun formatExpiryDate(expiryIso: String): String =
    runCatching { LocalDate.parse(expiryIso) }.getOrNull()
        ?.let { DateTimeFormatter.ofPattern("d MMM yyyy").format(it) }
        ?: expiryIso

@Composable
private fun DocumentStatusRow(label: String, expiryIso: String?) {
    val status = documentStatusFor(expiryIso)
    val (statusText, accent) = when (status) {
        DocumentStatus.VERIFIED -> "Verified" to CaptainPalette.success
        DocumentStatus.EXPIRING_SOON -> "Expiring soon" to CaptainPalette.warning
        DocumentStatus.EXPIRED -> "Expired" to CaptainPalette.danger
        DocumentStatus.UNKNOWN -> "Not on file" to CaptainPalette.textMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CaptainPalette.panel)
            .border(1.5.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
            Text(
                if (expiryIso != null) "Expires ${formatExpiryDate(expiryIso)}" else "Not on file",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = CaptainPalette.textSecondary,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(accent.copy(alpha = 0.16f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(statusText.uppercase(), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = accent)
        }
    }
}

/** "MMM yyyy" from a full `created_at` ISO instant (e.g. `UserDto.createdAt`) — same
 * Instant-then-OffsetDateTime-fallback parse [au.com.threesixty.cabdispatch.ui.screens.messages.formatMessageClockTime]
 * already uses for a backend timestamp that may or may not carry an explicit UTC offset. Returns
 * `null` (never a raw ISO string) on a genuinely unparseable value, so the identity row simply
 * omits itself rather than showing raw JSON-ish text. */
private fun formatMemberSince(iso: String): String? {
    val instant = runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull() ?: return null
    return DateTimeFormatter.ofPattern("MMM yyyy").format(instant.atZone(ZoneId.systemDefault()))
}

/** JPEG-encodes a captured preview [Bitmap] for upload — unchanged from before this visual pass. */
private fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return stream.toByteArray()
}

/** Mirrors backend `app.models.compliance.VALID_DOC_TYPES` labels — read-only placeholder fallback
 * when the dossier call fails/no session is bound. Unchanged from before this visual pass. */
private val FALLBACK_DOC_TYPES = listOf(
    "Calibration record",
    "Mounting photo",
    "Accuracy test",
    "Cl.14 checklist",
    "Camera register",
    "Duress alarm register",
    "Tracking register",
)
