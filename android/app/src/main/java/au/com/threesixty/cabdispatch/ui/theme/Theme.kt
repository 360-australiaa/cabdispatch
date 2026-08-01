package au.com.threesixty.cabdispatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Cab Dispatch brand palette — exact hex values from spec B1/CLAUDE instructions.
 * Keep these four as the single source of truth for the brand; the dashboard
 * (React/Tailwind) and this app must stay in visual lock-step with them.
 */
object CabDispatchColors {
    val Indigo = Color(0xFF2A1C58)
    val Gold = Color(0xFFF4C300)
    val Lavender = Color(0xFFEFEAF8)
    val Purple = Color(0xFF3A2774)
}

private val LightColors = lightColorScheme(
    primary = CabDispatchColors.Indigo,
    onPrimary = Color.White,
    secondary = CabDispatchColors.Gold,
    onSecondary = CabDispatchColors.Indigo,
    tertiary = CabDispatchColors.Purple,
    onTertiary = Color.White,
    background = CabDispatchColors.Lavender,
    onBackground = CabDispatchColors.Indigo,
    surface = Color.White,
    onSurface = CabDispatchColors.Indigo,
)

private val DarkColors = darkColorScheme(
    primary = CabDispatchColors.Gold,
    onPrimary = CabDispatchColors.Indigo,
    secondary = CabDispatchColors.Purple,
    onSecondary = Color.White,
    tertiary = CabDispatchColors.Lavender,
    onTertiary = CabDispatchColors.Indigo,
    background = CabDispatchColors.Indigo,
    onBackground = CabDispatchColors.Lavender,
    surface = CabDispatchColors.Purple,
    onSurface = Color.White,
)

/**
 * App-wide Material3 theme wrapper. S3 (Hired, passenger-visible fare) in
 * particular needs high contrast in direct sunlight — screens that need to
 * force the high-contrast look regardless of system theme should read
 * [CabDispatchColors] directly rather than [MaterialTheme.colorScheme].
 */
@Composable
fun CabDispatchTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
