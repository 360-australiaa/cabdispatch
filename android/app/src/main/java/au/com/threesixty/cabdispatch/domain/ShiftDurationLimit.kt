package au.com.threesixty.cabdispatch.domain

import java.time.Duration
import java.time.Instant
/**
 * Client-side "time remaining before shift-duration limit" estimate for the dashboard's live
 * countdown (2026-08-10 meter-polish pass, matching a real competitor taxi-meter UX pattern).
 *
 * **Honest simplification, not a full fatigue-management feature:** the backend already has a
 * real configured limit (`backend/app/core/config.py`'s `FATIGUE_SHIFT_DURATION_LIMIT_HOURS`,
 * default `12.0`, consumed server-side by `app/services/fatigue.py`) but there is no endpoint
 * today that exposes it to this client — `grep`ing the backend's `app/api/v1/` tree for the
 * constant's name turns up nothing outside `config.py`/`fatigue.py` themselves. Rather than block
 * this whole feature on a new backend endpoint (out of this pass's scope per its own brief), this
 * hardcodes the same default the backend ships with as [SHIFT_DURATION_LIMIT_HOURS] below. If a
 * tenant ever configures a non-default `FATIGUE_SHIFT_DURATION_LIMIT_HOURS` server-side, this
 * on-device countdown will silently disagree with the backend's real fatigue enforcement — that's
 * a real, live risk of this simplification, not a hypothetical one, flagged here and in
 * `HANDOFF.md` rather than left implicit. The fix, whenever someone picks it up, is a client-
 * readable field on whatever DTO already carries other tenant-level config (there isn't one
 * today either — see `ApiService.kt` — so this is a two-part gap: no endpoint AND no natural
 * existing DTO to hang it off).
 */
object ShiftDurationLimit {
    /** Mirrors the backend's `FATIGUE_SHIFT_DURATION_LIMIT_HOURS` default — see this file's own
     * doc for why this is a hardcoded mirror, not a live-read value. */
    const val SHIFT_DURATION_LIMIT_HOURS: Double = 12.0

    /**
     * Returns how much time is left before [SHIFT_DURATION_LIMIT_HOURS] is reached, given the
     * shift's ISO-8601 [shiftStartAtIso] (`DriverSession.shiftStartAt`) and [now] (defaulted to
     * the real current instant — a parameter purely so this is unit-testable on the JVM without
     * mocking a clock). `null` if [shiftStartAtIso] is null/unparseable (no shift, or the
     * pre-persisted-session gap [DriverSession] itself documents) — the caller (the dashboard
     * countdown chip) must treat `null` as "don't show the chip", never as zero remaining.
     *
     * A negative [Duration] means the limit has already been passed — deliberately returned as-is
     * (not clamped to [Duration.ZERO]) so the caller can distinguish "about to expire" from
     * "already over", which the reference UX this task matches renders differently (an overdue
     * warning state, not just "00:00").
     */
    fun remaining(shiftStartAtIso: String?, now: Instant = Instant.now()): Duration? {
        if (shiftStartAtIso.isNullOrBlank()) return null
        val startedAt = runCatching { Instant.parse(shiftStartAtIso) }
            .getOrElse { return null }
        val limit = Duration.ofMillis((SHIFT_DURATION_LIMIT_HOURS * 3_600_000L).toLong())
        val elapsed = Duration.between(startedAt, now)
        return limit.minus(elapsed)
    }
}
