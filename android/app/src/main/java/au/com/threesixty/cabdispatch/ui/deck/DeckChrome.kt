package au.com.threesixty.cabdispatch.ui.deck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** Live "Mon 10 Aug · 4:05 PM" clock, ticking on the minute. */
@Composable
fun rememberDeckClock(): String {
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.ENGLISH) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }
    return fmt.format(now)
}
