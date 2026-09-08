package au.com.threesixty.cabdispatch.domain

import java.time.LocalDate

/**
 * NSW gazetted public holidays used for Point to Point Transport (Fares) Order 2026
 * classification: the Peak Time Hiring Charge applies 10pm-6am on Friday, Saturday, OR the night
 * before a public holiday (see [au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s
 * `resolveIsPeak`/`resolveIsPeakFor`), and the country-area Holiday Distance Rate applies 6am-10pm
 * on Sundays and public holidays (`resolveTimeClass`/`resolveTimeClassFor`).
 *
 * Deliberately EXCLUDES the NSW Bank Holiday (1st Monday in August) — that one is a
 * public-sector-only holiday, not a general public holiday, and does not trigger either Fares
 * Order provision above.
 *
 * 2026 dates are the actual gazetted NSW public holidays. 2027 dates are calculated from the
 * standard fixed rules this state has applied consistently for years (Easter via the standard
 * computus; King's Birthday = 2nd Monday of June; Labour Day = 1st Monday of October; and the
 * Christmas/Boxing Day "falls on a weekend -> an extra public holiday is gazetted on the next
 * available weekday" convention) rather than transcribed from an official 2027 gazette, since one
 * does not yet exist this far out.
 *
 * TODO(risk flag): verify every 2027 date against the real NSW public holidays gazette once it is
 * published — these are a best-effort calculation from the standard rules, not an official
 * source, and the government has occasionally varied from the mechanical rule for a specific year
 * (e.g. shifting a clashing holiday to a different weekday than the "next Monday" default).
 */
object NswPublicHolidays {
    val DATES: Set<LocalDate> = setOf(
        // --- 2026 (gazetted) ---
        LocalDate.of(2026, 1, 1), // New Year's Day (Thu)
        LocalDate.of(2026, 1, 26), // Australia Day (Mon)
        LocalDate.of(2026, 4, 3), // Good Friday
        LocalDate.of(2026, 4, 4), // Easter Saturday
        LocalDate.of(2026, 4, 5), // Easter Sunday
        LocalDate.of(2026, 4, 6), // Easter Monday
        LocalDate.of(2026, 4, 25), // Anzac Day (Sat)
        LocalDate.of(2026, 6, 8), // King's Birthday (2nd Mon June)
        LocalDate.of(2026, 10, 5), // Labour Day (1st Mon October)
        LocalDate.of(2026, 12, 25), // Christmas Day (Fri)
        LocalDate.of(2026, 12, 26), // Boxing Day (Sat)
        LocalDate.of(2026, 12, 28), // Boxing Day holiday (Boxing Day falls on a Saturday)

        // --- 2027 (calculated from standard rules — TODO: verify against the gazette) ---
        LocalDate.of(2027, 1, 1), // New Year's Day (Fri)
        LocalDate.of(2027, 1, 26), // Australia Day (Tue)
        LocalDate.of(2027, 3, 26), // Good Friday
        LocalDate.of(2027, 3, 27), // Easter Saturday
        LocalDate.of(2027, 3, 28), // Easter Sunday
        LocalDate.of(2027, 3, 29), // Easter Monday
        LocalDate.of(2027, 4, 25), // Anzac Day (Sun)
        LocalDate.of(2027, 6, 14), // King's Birthday (2nd Mon June)
        LocalDate.of(2027, 10, 4), // Labour Day (1st Mon October)
        LocalDate.of(2027, 12, 25), // Christmas Day (Sat)
        LocalDate.of(2027, 12, 26), // Boxing Day (Sun)
        LocalDate.of(2027, 12, 27), // Christmas Day holiday (Christmas Day falls on a Saturday)
        LocalDate.of(2027, 12, 28), // Boxing Day holiday (Boxing Day falls on a Sunday)
    )

    fun isPublicHoliday(date: LocalDate): Boolean = date in DATES

    fun isDayBeforePublicHoliday(date: LocalDate): Boolean = date.plusDays(1) in DATES
}
