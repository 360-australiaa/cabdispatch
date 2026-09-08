package au.com.threesixty.cabdispatch.ui.screens.readiness

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.core.app.ActivityCompat
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.Icons
import android.Manifest
import au.com.threesixty.cabdispatch.ui.theme.PAIR_CODE_ALPHABET
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.domain.AppUpdateState
import au.com.threesixty.cabdispatch.domain.KioskLockController
import au.com.threesixty.cabdispatch.domain.RuntimePermissions
import au.com.threesixty.cabdispatch.domain.DeviceReadiness
import au.com.threesixty.cabdispatch.ui.overlays.CaptainChromeMetrics
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PAIR_CODE_LENGTH
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * The device-readiness gate — a tablet must be fit to work before anyone can log into the meter.
 *
 * Sits between the terms disclaimer and the login screen (see
 * [au.com.threesixty.cabdispatch.ui.navigation.postAuthDestination]), and is modelled on
 * [au.com.threesixty.cabdispatch.ui.screens.terms.TermsDisclaimerScreen] — this app's canonical
 * "you cannot proceed until…" surface, and the same two-column shell the permissions checklist
 * uses. Like that screen there is no "continue anyway": the whole point is that an unregistered
 * tablet does not take fares.
 *
 * Everything it shows is real. The pairing keypad performs an actual registration, the update
 * action drives the actual [au.com.threesixty.cabdispatch.domain.AppUpdateChecker] download +
 * SHA-256 verify + system installer prompt, and the offline-map action downloads actual tiles. A
 * check that has not been probed yet says "Not checked" rather than pretending to a verdict.
 *
 * It clears itself: as soon as the blocking set empties (which pairing does immediately, since it
 * writes `deviceId`) [onReady] fires and the driver continues. There is no confirm step to tap —
 * a driver who has just fixed the thing they were told to fix should not have to acknowledge it.
 */
@Composable
fun DeviceReadinessScreen(
    onReady: () -> Unit,
    viewModel: DeviceReadinessViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.ready) {
        if (state.ready) onReady()
    }

    // The app-level banners announce the very conditions this screen exists to fix, in fewer words
    // and with no fix attached -- and, having no header to measure, they land on this screen's own
    // headline. Stand them down for as long as the gate is up.
    DisposableEffect(Unit) {
        CaptainChromeMetrics.setFullScreenGateVisible(true)
        onDispose { CaptainChromeMetrics.setFullScreenGateVisible(false) }
    }

    // The system permission dialog is a separate window; nothing tells this screen the technician
    // answered it. Re-read on resume so the row ticks over the moment they come back, rather than
    // sitting on a stale cross that makes a granted permission look denied.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Every special-access answer arrives this way and only this way: a permission
                // dialog, a Settings screen, Android's own pin confirmation. None of them has a
                // result callback -- coming back IS the result.
                viewModel.refreshDeviceState(
                    kioskMode = (context as? Activity)?.let(KioskLockController::currentLockTaskMode),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .padding(start = 72.dp, end = 72.dp, top = 48.dp, bottom = 36.dp),
    ) {
        // Left column — what is wrong and why it matters, with the app stamp for a depot phone call.
        Column(modifier = Modifier.width(392.dp)) {
            Text(
                if (state.commissioning) "Set up this tablet" else "Meter not ready",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                color = CaptainPalette.textPrimary,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                if (state.commissioning) {
                    "Work down the list before handing this tablet to a driver. Everything with a " +
                        "cross has a button to fix it. The driver will never see this screen — they " +
                        "go straight to the login page."
                } else {
                    "This tablet must be registered with the depot before it can take fares. " +
                        "Ask the depot for a pairing code — they generate one from Fleet ▸ Vehicles ▸ Pair."
                },
                fontFamily = InterFamily,
                fontSize = 17.sp,
                lineHeight = 26.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.width(380.dp),
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CaptainPalette.raised)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontFamily = RobotoMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
            Spacer(Modifier.weight(1f))

            if (state.commissioning) {
                // Enabled as soon as nothing BLOCKS, with any remaining warnings counted on the
                // label rather than hidden. A tablet commissioned indoors will never get a GPS fix
                // no matter how long the technician waits, so refusing to let them finish would
                // only teach them to work around this screen.
                val warnings = state.warnings.size
                CaptainButton(
                    text = when {
                        !state.canFinishSetup -> "Fix the items above"
                        warnings == 0 -> "Finish setup"
                        warnings == 1 -> "Finish setup · 1 warning"
                        else -> "Finish setup · $warnings warnings"
                    },
                    modifier = Modifier.width(320.dp),
                    heightDp = 64,
                    enabled = state.canFinishSetup,
                    onClick = { viewModel.finishSetup(onReady) },
                )
                if (warnings > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // Named, not counted. A technician signing off with warnings is taking
                        // responsibility for them, and cannot do that against a number.
                        "Signing off: " + state.warnings.joinToString(", ") { it.check.label() },
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = CaptainPalette.warning,
                        modifier = Modifier.width(320.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Same escape as the disclaimer's Cancel: close the app. Deliberately not "continue
            // anyway" -- there is no such thing here.
            CaptainButton(
                text = "Close",
                outline = true,
                modifier = Modifier.width(220.dp),
                heightDp = 64,
            ) {
                (context as? ComponentActivity)?.finishAffinity()
            }
        }

        Spacer(Modifier.width(56.dp))

        // Right column — the checklist, then whichever fix the top blocking failure needs.
        //
        // Scrollable as a backstop. The content is sized to fit the 800dp design canvas, but it
        // grows with the number of failing checks and the keypad is the last thing in it: the
        // first build of this screen ran the bottom key row and both pairing buttons off the
        // bottom of the tablet, which on a gate with no way past is not a cosmetic problem.
        Column(
            modifier = Modifier
                .weight(1f)
                // Insets above the soft keyboard, so the Pair button stays scrollable-to while it
                // is up rather than sitting underneath it. (The keyboard's own Done key submits
                // too -- see keyboardActions below -- but a driver should not have to know that.)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 26.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.results.forEach { ReadinessRow(it) }
            }

            Spacer(Modifier.height(18.dp))

            val blockingChecks = state.blocking.map { it.check }
            when {
                DeviceReadiness.ReadinessCheck.Registered in blockingChecks -> PairPanel(
                    code = state.pairCode,
                    pairing = state.pairing,
                    error = state.pairError,
                    onCodeChange = viewModel::onPairCodeChange,
                    onScan = { (context as? Activity)?.let(viewModel::scanPairingQr) },
                    onSubmit = viewModel::submitPairCode,
                )

                DeviceReadiness.ReadinessCheck.UpToDate in blockingChecks -> UpdatePanel(
                    updateState = state.updateState,
                    onAction = { (context as? Activity)?.let(viewModel::installUpdate) },
                )
            }

            Spacer(Modifier.height(18.dp))

            // Advisory fix, always offered: the map row is the one advisory check with an action a
            // driver can usefully take while they are standing here anyway.
            val outstanding = state.results.filter { !it.passed }.map { it.check }.toSet()

            if (DeviceReadiness.ReadinessCheck.Permissions in outstanding) {
                PermissionsPanel(
                    missing = state.missingPermissions,
                    onGrant = { permission ->
                        (context as? Activity)?.let { activity -> grantPermission(activity, permission) }
                    },
                )
                Spacer(Modifier.height(10.dp))
            }

            if (DeviceReadiness.ReadinessCheck.Kiosk in outstanding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CaptainButton(
                        text = "Pin the meter",
                        outline = true,
                        modifier = Modifier.width(300.dp),
                        heightDp = 56,
                        fontSize = 16.sp,
                        onClick = {
                            (context as? Activity)?.let {
                                KioskLockController.applyKioskLock(it, desiredLocked = true)
                            }
                        },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Android will ask you to confirm",
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (DeviceReadiness.ReadinessCheck.BatteryOptimisation in outstanding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CaptainButton(
                        text = "Exempt from battery optimisation",
                        outline = true,
                        modifier = Modifier.width(300.dp),
                        heightDp = 56,
                        fontSize = 16.sp,
                        onClick = {
                            context.startActivity(RuntimePermissions.batteryOptimisationIntent(context))
                        },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Find this app in the list and allow it",
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (DeviceReadiness.ReadinessCheck.VehicleClass in outstanding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CaptainButton(
                        text = "Standard taxi",
                        outline = true,
                        modifier = Modifier.width(180.dp),
                        heightDp = 56,
                        fontSize = 16.sp,
                        onClick = { viewModel.declareVehicleClass(isMaxi = false) },
                    )
                    Spacer(Modifier.width(12.dp))
                    CaptainButton(
                        text = "Maxi (5+ seats)",
                        outline = true,
                        modifier = Modifier.width(180.dp),
                        heightDp = 56,
                        fontSize = 16.sp,
                        onClick = { viewModel.declareVehicleClass(isMaxi = true) },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        // The consequence, not the setting name -- a technician picking the wrong
                        // one here mis-prices every fare in this vehicle.
                        "A maxi charges 150% of the metered fare",
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (DeviceReadiness.ReadinessCheck.Location in outstanding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CaptainButton(
                        text = "Grant location permission",
                        outline = true,
                        modifier = Modifier.width(300.dp),
                        heightDp = 56,
                        fontSize = 16.sp,
                        onClick = { (context as? Activity)?.let(::requestLocationPermission) },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        // Says which half is missing, because they need different actions: a denied
                        // permission is a dialog, a missing fix is a walk outside.
                        "Then wait for a fix — take the tablet outside if it does not arrive",
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (DeviceReadiness.ReadinessCheck.OfflineMaps in outstanding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CaptainButton(
                        text = "Download offline maps",
                        outline = true,
                        modifier = Modifier.width(300.dp),
                        heightDp = 56,
                        fontSize = 16.sp,
                        onClick = viewModel::downloadOfflineMaps,
                    )
                    state.mapDownloadMessage?.let {
                        Spacer(Modifier.width(16.dp))
                        Text(it, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.textSecondary)
                    }
                }
            }
        }
    }
}

/**
 * The permissions still missing, each with its own button.
 *
 * Expanded rather than a count, because a technician cannot act on "2 missing" — and because the
 * three kinds need different things: an ordinary dialog, a Settings screen, or (for background
 * location) a second dialog that only works once foreground location is already held.
 */
@Composable
private fun PermissionsPanel(
    missing: List<DeviceReadiness.MeterPermission>,
    onGrant: (DeviceReadiness.MeterPermission) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        missing.forEach { permission ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            permission.label,
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = CaptainPalette.textPrimary,
                        )
                        if (permission.critical) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "meter cannot work without this",
                                fontFamily = InterFamily,
                                fontSize = 12.sp,
                                color = CaptainPalette.warning,
                            )
                        }
                    }
                    Text(
                        // What it is FOR, not what it is called -- so a technician granting it
                        // knows what they are agreeing to on the driver's behalf.
                        "Needed for ${permission.needs}",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
                Spacer(Modifier.width(16.dp))
                CaptainButton(
                    text = "Grant",
                    outline = true,
                    modifier = Modifier.width(120.dp),
                    heightDp = 48,
                    fontSize = 15.sp,
                    onClick = { onGrant(permission) },
                )
            }
        }
    }
}

/**
 * Grants one permission, by whichever mechanism it actually needs.
 *
 * Install-packages is not a dialog at all -- it is a Settings screen; background location must be
 * asked for alone and only after foreground location is held, or Android 11+ denies the whole
 * request silently. The result comes back on resume, not through a callback.
 */
private fun grantPermission(activity: Activity, permission: DeviceReadiness.MeterPermission) {
    RuntimePermissions.settingsIntentFor(activity, permission)?.let {
        activity.startActivity(it)
        return
    }
    val request = when (permission) {
        DeviceReadiness.MeterPermission.BackgroundLocation ->
            arrayOf(RuntimePermissions.backgroundLocationPermission)
        else -> RuntimePermissions.foregroundRequestArray()
    }
    ActivityCompat.requestPermissions(activity, request, LOCATION_PERMISSION_REQUEST_CODE)
}

/**
 * Asks for the meter-critical location permission.
 *
 * Uses the plain Activity request rather than a Compose launcher because this screen may be the
 * very first thing composed on a fresh install and the technician can arrive here from several
 * routes; the result is picked up on resume by the observer above, not by a callback, so it works
 * however the dialog was dismissed.
 */
private fun requestLocationPermission(activity: Activity) {
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        LOCATION_PERMISSION_REQUEST_CODE,
    )
}

private const val LOCATION_PERMISSION_REQUEST_CODE = 4801

/** One check: a tick or a cross, its name, and the honest detail line from [DeviceReadiness]. */
@Composable
private fun ReadinessRow(result: DeviceReadiness.ReadinessResult) {
    val tone = when {
        result.passed -> CaptainPalette.success
        result.severity == DeviceReadiness.Severity.BLOCKING -> CaptainPalette.danger
        else -> CaptainPalette.warning
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A real tick or cross, not a coloured dot. A technician working down this list on a
        // vehicle needs to see at a glance which items are done, and a dot only says "something
        // about this is amber" -- it does not say done or not done.
        Icon(
            imageVector = if (result.passed) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
            contentDescription = if (result.passed) "Passed" else "Not done",
            tint = tone,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.check.label(),
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = CaptainPalette.textPrimary,
                )
                // Says out loud which rows can actually stop you, so a driver is not left guessing
                // whether an amber line is the thing keeping them out.
                if (!result.passed && result.severity == DeviceReadiness.Severity.ADVISORY) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "does not block",
                        fontFamily = InterFamily,
                        fontSize = 12.sp,
                        color = CaptainPalette.textMuted,
                    )
                }
            }
            // Only a check that has NOT passed explains itself. A green dot next to "Signed
            // tariff" already says everything "Signed tariff cached" would, and five explanations
            // pushed the Pair button off the bottom of the tablet -- on a gate, the driver needs
            // to read what is WRONG, not be congratulated four times on the way to it.
            if (!result.passed) {
                Text(
                    result.detail,
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
        }
    }
}

private fun DeviceReadiness.ReadinessCheck.label(): String = when (this) {
    DeviceReadiness.ReadinessCheck.Registered -> "Registered with the depot"
    DeviceReadiness.ReadinessCheck.UpToDate -> "Meter software"
    DeviceReadiness.ReadinessCheck.Permissions -> "Permissions"
    DeviceReadiness.ReadinessCheck.Location -> "Location \u0026 GPS"
    DeviceReadiness.ReadinessCheck.BatteryOptimisation -> "Battery optimisation"
    // "Screen pinned", not "Kiosk lock": this app holds no Device Owner provisioning, so what it
    // can actually do is Android screen pinning — escapable, and gone after a reboot until the
    // next heartbeat re-applies it. See DeviceReadiness.ReadinessCheck.Kiosk's doc; the row's own
    // detail line carries the limits. The word "kiosk" is kept for a real DPC lock only.
    DeviceReadiness.ReadinessCheck.Kiosk -> "Screen pinned"
    DeviceReadiness.ReadinessCheck.MapService -> "Map service"
    DeviceReadiness.ReadinessCheck.VehicleClass -> "Vehicle class"
    DeviceReadiness.ReadinessCheck.OfflineMaps -> "Offline maps"
    DeviceReadiness.ReadinessCheck.SignedTariff -> "Signed tariff"
    DeviceReadiness.ReadinessCheck.Heartbeat -> "Depot heartbeat"
}

/**
 * Pairing-code entry, on the tablet's own keyboard.
 *
 * Every other text entry in this app is a hand-rolled on-screen keypad, and `HiredScreen.kt`'s
 * destination dialog documents why: on-device verification found that tapping a real `TextField`
 * there never raised the soft keyboard (`dumpsys input_method` reported `mShowRequested=false`
 * even with a Compose input connection attached). That finding is respected, not ignored -- it was
 * re-measured on this screen, on the same tablet, before this field shipped, and the keypad is
 * what comes back if the IME cannot be raised here either.
 *
 * Uses the same registration call as Settings ▸ Pair Meter ([DevicePairingRepository]).
 */
@Composable
private fun PairPanel(
    code: String,
    pairing: Boolean,
    error: String?,
    onCodeChange: (String) -> Unit,
    onScan: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 26.dp, vertical = 16.dp),
    ) {
        Text(
            "Pairing code",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            color = CaptainPalette.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        val focusRequester = remember { FocusRequester() }
        // Focused on arrival so the tablet's own keyboard comes straight up -- a driver on a gate
        // they cannot leave should not have to work out that the field is tappable first.
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        TextField(
            value = code,
            // Filtered here rather than by hiding keys: pairing codes exclude 0/1/O/I server-side
            // (fleet_service._PAIRING_CODE_ALPHABET, to avoid transcription ambiguity), so a
            // character the server would reject is dropped as it is typed. Uppercased for the same
            // reason -- the server compares the code exactly.
            onValueChange = { raw ->
                onCodeChange(raw.uppercase().filter { it in PAIR_CODE_ALPHABET }.take(PAIR_CODE_LENGTH))
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrect = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (code.length == PAIR_CODE_LENGTH) onSubmit() }),
            placeholder = {
                Text(
                    "\u2013 \u2013 \u2013 \u2013 \u2013 \u2013 \u2013 \u2013",
                    fontFamily = RobotoMonoFamily,
                    fontSize = 26.sp,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center,
                    color = CaptainPalette.textMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CaptainPalette.raised,
                unfocusedContainerColor = CaptainPalette.raised,
                focusedTextColor = CaptainPalette.textPrimary,
                unfocusedTextColor = CaptainPalette.textPrimary,
                cursorColor = CaptainPalette.accent,
                focusedIndicatorColor = CaptainPalette.accent,
                unfocusedIndicatorColor = CaptainPalette.panelBorder,
            ),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.danger)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptainButton(
                text = "Scan QR",
                outline = true,
                modifier = Modifier.weight(1f),
                heightDp = 56,
                fontSize = 17.sp,
                onClick = onScan,
            )
            CaptainButton(
                text = if (pairing) "Pairing…" else "Pair",
                modifier = Modifier.weight(1f),
                heightDp = 56,
                fontSize = 17.sp,
                // Only a complete code can possibly be valid, so an incomplete one never gets sent
                // just to come back as an error.
                enabled = code.length == PAIR_CODE_LENGTH && !pairing,
                onClick = onSubmit,
            )
        }
    }
}

/**
 * The forced-update fix. Every label is the real [AppUpdateState] — this never claims an install
 * happened, because after the system installer is launched this app genuinely cannot observe
 * whether the driver accepted it (see [au.com.threesixty.cabdispatch.domain.AppUpdateChecker]).
 */
@Composable
private fun UpdatePanel(updateState: AppUpdateState, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 28.dp, vertical = 22.dp),
    ) {
        val (message, action) = when (updateState) {
            is AppUpdateState.Available ->
                "Version ${updateState.release.versionName} is ready to download." to "Download update"
            is AppUpdateState.Downloading ->
                "Downloading… ${updateState.percent}%" to null
            is AppUpdateState.Verifying ->
                "Checking the download is intact…" to null
            is AppUpdateState.ReadyToInstall ->
                "Downloaded and verified. Tap to install, then confirm on the system prompt." to "Install update"
            is AppUpdateState.Failed ->
                updateState.message to "Retry"
            else -> "Checking for a newer build…" to null
        }
        Text(message, fontFamily = InterFamily, fontSize = 17.sp, color = CaptainPalette.textSecondary)
        action?.let {
            Spacer(Modifier.height(16.dp))
            CaptainButton(text = it, modifier = Modifier.width(320.dp), heightDp = 60, fontSize = 17.sp, onClick = onAction)
        }
    }
}
