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
 * Honesty deviations, flagged: the LICENCE / AUTHORITY identity rows have no backing fields on
 * [DriverSession] (name/driverId/vehicleId only) so they are not rendered; the status strip is
 * dashboard-owned state and is omitted. [onFactoryReset] stays in the signature (the NavHost
 * contract) even though the embedded Settings host lives on its own route.
 */
@Composable
fun ProfileScreen(
    navController: NavHostController,
    onFactoryReset: () -> Unit,
    viewModel: ProfileViewModel = viewModel(),
) {
    val session by SessionHolder.session.collectAsState()

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
        Text("Driver profile", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = CaptainPalette.textPrimary)
        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            IdentityCard(session, viewModel)
            ComplianceColumn(viewModel = viewModel, expiryItems = expiryItems, modifier = Modifier.weight(1f))
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
                session?.vehicleId?.let { IdentityAttribute("VEHICLE", it) }
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
