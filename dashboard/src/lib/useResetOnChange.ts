import { useEffect, useRef } from "react";

/**
 * "Reset this form's local state when the thing it is editing changes."
 *
 * Every form modal in this dashboard holds its fields in `useState` and needs
 * to re-seed them when it is opened, or when the row it is pointed at is
 * swapped underneath it. The shape everyone reached for was:
 *
 * ```ts
 * useEffect(() => {
 *   if (open) {
 *     setForm(voucher ? formFromVoucher(voucher) : emptyForm());
 *     setError(null);
 *   }
 *   // eslint-disable-next-line react-hooks/exhaustive-deps
 * }, [open, voucher?.id]);
 * ```
 *
 * The suppression is load-bearing, not laziness: the effect body reads
 * `voucher` (and often a mutation object) but the deps list only its `id`,
 * because depending on the whole object would re-run the reset on every
 * refetch that produced a new-but-equal row — wiping what the operator had
 * typed mid-edit. The audit found **22** of these, all the same pattern
 * (`docs/audits/2026-09-08-dashboard-audit.md` §6), which meant 22 places
 * where the lint rule was off and a *genuine* missing dependency would go
 * unnoticed alongside the intended one.
 *
 * This hook makes the intent explicit instead of suppressing the rule:
 *
 * ```ts
 * useResetOnChange(open ? voucher?.id ?? "new" : null, () => {
 *   setForm(voucher ? formFromVoucher(voucher) : emptyForm());
 *   setError(null);
 * });
 * ```
 *
 * The `reset` callback is held in a ref and deliberately **not** a dependency,
 * so it may close over anything (props, mutations, other state) without being
 * memoised and without re-running the reset. That is the one exception this
 * hook encapsulates, in one reviewed place, rather than 22 unreviewed ones —
 * and it is safe precisely because `reset` is only ever *called* in response
 * to `key` changing, never *compared*.
 *
 * @param key   What identity the form is bound to. Pass `null`/`undefined`/
 *              `false` to mean "not currently showing anything", which
 *              suppresses the reset — so closing a modal does not fire it, and
 *              re-opening on the same row does (the key goes null and back).
 *              Use a primitive: an id, or a template string of several ids.
 *              Compared with `Object.is`.
 * @param reset What to re-seed. Called during the effect phase, after the
 *              render in which `key` changed.
 */
export function useResetOnChange(key: unknown, reset: () => void): void {
  const resetRef = useRef(reset);
  resetRef.current = reset;

  // `undefined` is a value a caller could plausibly pass as a real key, so the
  // "nothing seen yet" sentinel has to be something a caller cannot produce.
  const previousKey = useRef<unknown>(NOTHING_SEEN_YET);

  useEffect(() => {
    if (key == null || key === false) {
      // Not showing anything. Forget the last key so that re-opening on the
      // *same* row still counts as a change and re-seeds the form, rather
      // than leaving the operator looking at whatever they typed last time.
      previousKey.current = NOTHING_SEEN_YET;
      return;
    }
    if (Object.is(previousKey.current, key)) return;
    previousKey.current = key;
    resetRef.current();
  }, [key]);
}

/** Not exported, and not constructible from outside this module, so it can
 * never collide with a key a caller passes. */
const NOTHING_SEEN_YET = Symbol("useResetOnChange/nothing-seen-yet");
