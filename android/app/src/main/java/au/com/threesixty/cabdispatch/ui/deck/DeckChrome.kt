package au.com.threesixty.cabdispatch.ui.deck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Command Deck chrome — what survives of the v2 redesign's three fixed layout regions.
 *
 * The 44dp persistent status strip (`DeckStatusStrip`/`StripStatus`), the 92dp left nav rail
 * (`DeckNavRail`, `DeckNavRailCarousel`) and the `DeckScaffold` that composed them around a
 * content slot were deleted in Phase 0 (P0.3). They existed only for the wheel/dock dashboard
 * variants, which are gone; the live post-login home
 * ([au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]) draws its own header and
 * nav rail inline and never called them. Nothing outside those deleted files referenced them.
 *
 * [rememberDeckClock] is the one member with live callers — `CloseAndPayScreen` renders it in two
 * places — so it stays here, in this package, rather than moving and churning that import.
 */

/**
 * Live "Mon 10 Aug · 4:05 PM" clock, ticking on the minute.
 *
 * `now` is written every second (so the minute rolls over within a second of the real clock, not
 * up to 59s late), but the pattern this formats to has no seconds field, so the STRING this
 * returns only actually changes once every 60 writes. Before this [derivedStateOf] wrap (W5
 * recomposition-hygiene pass, 2026-09-12), every caller of this function — including
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.CaptainHeader], composed for the entire time
 * a driver is logged in — recomposed once a second, all shift, to redraw an unchanged string 59
 * times out of 60. Reading a derived `State<String>` instead of the raw `now` state means a
 * caller's own recomposition scope is only invalidated when the FORMATTED value actually differs,
 * not on every one-second write to the underlying clock.
 */
@Composable
fun rememberDeckClock(): String {
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.ENGLISH) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    // Unit is correct here, not a missing key: this effect's OWN body is the ticking loop (an
    // infinite `while (true) { … delay(1_000) }`), so there is no external dependency to key on —
    // restarting it on some other value changing would just reset the same loop. It launches once
    // per composition and runs for the composable's whole lifetime, exactly as a clock should.
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }
    val formatted by remember { derivedStateOf { fmt.format(now) } }
    return formatted
}
