package au.com.threesixty.cabdispatch.ui.theme

/**
 * What a device pairing code is made of, mirroring the server's own definition
 * (`fleet_service.PAIRING_CODE_LENGTH` / `_PAIRING_CODE_ALPHABET`).
 *
 * Two surfaces take a pairing code — the device-readiness gate in front of the login screen, and
 * Settings ▸ About ▸ Pair Meter — and both filter input to this alphabet as it is typed, so a
 * character the server would reject never reaches the wire.
 *
 * `0/1/O/I` are excluded so a code read aloud down a phone line cannot be transcribed ambiguously.
 * That exclusion used to be enforced by a hand-rolled on-screen keypad that simply omitted those
 * keys. The keypad is gone: every text entry in this app avoids the platform IME because an early
 * on-device check found it never appeared (see `HiredScreen.kt`'s `DestinationSearchDialog`), but
 * re-measuring on the target tablet on 2026-09-08, screen-pinned, showed otherwise —
 * `dumpsys input_method` reported `mShowRequested=true` / `mInputShown=true` and typed characters
 * landed. The tablet's own keyboard is better than anything hand-rolled here, so the codes use it
 * and the rule moved from "which keys exist" to this filter.
 */
const val PAIR_CODE_LENGTH = 8

/** See [PAIR_CODE_LENGTH]. A-Z and 2-9, minus the ambiguous `0/1/O/I`. */
const val PAIR_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
