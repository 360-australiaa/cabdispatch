package au.com.threesixty.cabdispatch.ui.screens.closepay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.RatePassengerHandoff
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.ui.deck.rememberDeckClock
import au.com.threesixty.cabdispatch.ui.overlays.reportsChromeHeader
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.Space
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * S4 — Close & Pay, re-skinned onto the [CaptainPalette] purple design system (2026-08-29 pass).
 * All state/logic lives unchanged in [CloseAndPayViewModel] — this file is purely the visual layer
 * over the same [CloseAndPayUiState]/[PaymentSubScreen] machine; every `onClick` still calls the
 * exact same ViewModel method with the same arguments it always did. Cash/CabCharge entry keep
 * their numeric-pad-driven sub-screens (now on `CaptainKeypad`); Voucher/Account/Split/Receipt are
 * restyled in place. Previously ported off the old yellow/black `Deck` tokens this pass replaces.
 *
 * ### W7 file split (2026-09-13)
 * This file used to be 1,629 lines holding every S4 sub-screen. It is now the orchestrating
 * top-level screen only — [CloseAndPayScreen] itself, [ReadyToCloseFlow]'s sub-screen switch, and
 * the small shared bits ([DefaultRatesNotice], [CenterMessage], [ClosingStatusStrip],
 * [PaymentSubScreen]) — split into:
 * - `PaymentMethodSheet.kt` — the method picker and every payment-method entry screen
 *   (Cash/CabCharge/Voucher/Account/Split Fare) [ReadyToCloseFlow] below switches between.
 * - `ReceiptOptions.kt` — the final receipt step ([ReceiptScreen]) this file's own
 *   [CloseAndPayScreen] composable still calls.
 * - `FareSummaryCard.kt` — the itemised fare-breakdown card (`TotalCol`) and its cleaning-fee/tip
 *   edit dialogs, shown inside the method picker.
 *
 * All three are the same package (`ui.screens.closepay`) as this file, so every cross-file call is
 * a same-package call — nothing here changed shape, only which file each composable lives in. Each
 * split-out composable this file (or `ReadyToCloseFlow`) still calls across a file boundary is
 * marked `internal` rather than `private` in its new home; anything only ever called from within
 * its own new file stayed `private` there.
 */
@Composable
fun CloseAndPayScreen(
    navController: NavHostController,
    onDone: () -> Unit,
    viewModel: CloseAndPayViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Rate Passenger hand-off (2026-09-04): CloseAndPayUiState.Done carries no fields of its own
    // (see that state's doc), so the just-closed trip's clientUuid is captured here — off the
    // ReceiptStep this screen is already rendering — the moment it's known, then handed to the
    // new post-trip screen via RatePassengerHandoff (same no-nav-graph-argument convention
    // TripDetailHandoff already established) right before this screen's existing onDone() fires.
    // Purely additive wiring: onDone()'s own contract/call site (CabDispatchNavHost) is unchanged
    // here — only what it now navigates *to* changed, in the nav host itself.
    var closedTripClientUuid by remember { mutableStateOf<String?>(null) }
    val receiptTripId = (state as? CloseAndPayUiState.ReceiptStep)?.receipt?.tripId
    LaunchedEffect(receiptTripId) {
        if (receiptTripId != null) closedTripClientUuid = receiptTripId
    }
    LaunchedEffect(state) {
        if (state is CloseAndPayUiState.Done) {
            closedTripClientUuid?.let { RatePassengerHandoff.set(it) }
            onDone()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        when (val s = state) {
            CloseAndPayUiState.Loading -> CenterMessage("Loading trip…")
            CloseAndPayUiState.NoActiveTrip -> CenterMessage("No active trip to close.")
            is CloseAndPayUiState.LoadError -> CenterMessage(s.message, isError = true)
            is CloseAndPayUiState.ReadyToClose -> {
                ReadyToCloseFlow(s, viewModel, navController)
                // F11: when no cached tariff row could be resolved, the fare below was computed
                // from the built-in Fares Order card rather than this operator's own. Payment is
                // no longer blocked (see ReadyToClose.usingDefaultRates' doc for why blocking was
                // the worse harm) -- but it must never be silent either, so this sits over the
                // flow rather than inside it, where no sub-screen of the payment flow can scroll
                // it out of sight before the driver takes the money.
                if (s.usingDefaultRates) DefaultRatesNotice()
            }
            is CloseAndPayUiState.ReceiptStep -> ReceiptScreen(s, viewModel)
            CloseAndPayUiState.Done -> Unit
        }
    }
}

/**
 * The "this fare used default rates" banner -- F11's visible half.
 *
 * Deliberately loud (warning-coloured, top-anchored, unmissable) and deliberately not dismissible:
 * it states a fact about the fare on screen that stays true for as long as that fare is on screen,
 * and a driver who taps it away would be taking payment on a rate card they have no reason to think
 * is not their operator's.
 */
@Composable
private fun DefaultRatesNotice() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Text(
            "DEFAULT RATES — this operator's tariff was not available on this device; " +
                "the fare below was calculated from the standard NSW Fares Order rate card.",
            fontFamily = InterFamily,
            fontSize = 13.sp,
            color = CaptainPalette.hudBg,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(CaptainPalette.warning)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CenterMessage(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontFamily = InterFamily, fontSize = 18.sp, color = if (isError) CaptainPalette.danger else CaptainPalette.textSecondary)
    }
}

/** `internal`, not `private`: `PaymentMethodSheet.kt`'s `MethodPickerScreen`/`CashCalculatorScreen`/
 * etc. all take/return this type — see this file's own "W7 file split" doc note. */
internal enum class PaymentSubScreen { METHOD_PICKER, CASH_CALCULATOR, CABCHARGE_ENTRY, VOUCHER_ENTRY, ACCOUNT_ENTRY, SPLIT_FARE_ENTRY }

// --- 19 · Close & Pay (method picker) + shared totalCol -------------------------------------

@Composable
private fun ReadyToCloseFlow(state: CloseAndPayUiState.ReadyToClose, vm: CloseAndPayViewModel, navController: NavHostController) {
    var subScreen by rememberSaveable { mutableStateOf(PaymentSubScreen.METHOD_PICKER) }

    Column(modifier = Modifier.fillMaxSize()) {
        ClosingStatusStrip()
        // Chip lane: the app-level status chips sit just under the strip; every sub-screen's
        // headline starts below them (tablet, 2026-09-08: FLEET LOCKED clipped the first letter).
        Spacer(Modifier.height(Space.lg))
        Box(modifier = Modifier.weight(1f)) {
            // Premium pass (2026-08-29): sub-screens previously hard-cut. Entering a method's
            // entry flow slides in from the right; returning to the picker slides back from the
            // left — spatial continuity for the "drill in / back out" mental model. Purely
            // presentational: the PaymentSubScreen state machine and every callback are unchanged.
            AnimatedContent(
                targetState = subScreen,
                transitionSpec = {
                    val forward = initialState == PaymentSubScreen.METHOD_PICKER
                    val dir = if (forward) 1 else -1
                    (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { dir * it / 16 })
                        .togetherWith(fadeOut(tween(180)))
                },
                label = "pay-subscreen",
            ) { sub ->
                when (sub) {
                PaymentSubScreen.METHOD_PICKER -> MethodPickerScreen(
                    state = state,
                    vm = vm,
                    onSelect = { method, next -> vm.selectPaymentMethod(method); subScreen = next },
                    onBackToMeter = { navController.popBackStack() },
                    onDispute = {
                        TripDetailHandoff.set(state.trip.clientUuid)
                        navController.navigate(CabDispatchRoutes.TRIP_DETAIL)
                    },
                )
                PaymentSubScreen.CASH_CALCULATOR -> CashCalculatorScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                PaymentSubScreen.CABCHARGE_ENTRY -> DocketEntryScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                PaymentSubScreen.VOUCHER_ENTRY -> VoucherEntryScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                PaymentSubScreen.ACCOUNT_ENTRY -> AccountEntryScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                PaymentSubScreen.SPLIT_FARE_ENTRY -> SplitFareEntryScreen(state, vm) { subScreen = PaymentSubScreen.METHOD_PICKER }
                }
            }
        }
    }
}

/** HUD-kit status strip with a "HIRED — CLOSING" [HudStatusPill] — this route has no drive-panel/
 * live GPS source of its own, so only the fields this screen genuinely knows (clock) render. */
@Composable
private fun ClosingStatusStrip() {
    // A Row, not a Box with two centre-aligned children (tablet, 2026-09-08): the pill grew to
    // near full width and printed "TRIP · CLOSING" straight over the clock. Pill at the start,
    // clock at the end, a weighted gap between -- they cannot meet whatever width either takes.
    //
    // reportsChromeHeader(): this strip IS this screen's top chrome. Without reporting it, the
    // app-level chips positioned themselves off the last header they had measured -- the 120dp
    // home header -- and FLEET LOCKED landed across the "Close & Pay" headline. Reporting the
    // real 64dp puts the chip lane just under this strip, above the content.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .reportsChromeHeader()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The pill fills whatever width it is given, so it is boxed into the weighted slot and
        // the clock keeps its own intrinsic width at the end -- otherwise the clock was pushed
        // clean off the right edge (tablet, 2026-09-08).
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            HudStatusPill(
                label = "Trip",
                value = "CLOSING",
                tone = HudTone.Danger,
            )
        }
        Text(
            rememberDeckClock(),
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
        )
    }
}
