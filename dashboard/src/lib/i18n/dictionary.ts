/**
 * The dashboard's one and only translation dictionary today.
 *
 * WAVE 3 (D6) NOTE — honesty over completeness: this is the *seam*, not a
 * finished translation of the product. Per the dashboard audit (§4, WS-F.3)
 * the app has ~280+ hardcoded English JSX text nodes across `pages/**` and
 * `components/**` (rough count via
 * `grep -rEo '>[A-Z][a-zA-Z0-9 ,.'"'"'&/-]{3,60}<' src/pages src/components | wc -l`
 * on this branch). Rewriting every one of them to `t("...")` in this
 * workstream would be a purely mechanical, high-risk, low-value diff across
 * files owned by other workstreams (D2, D8-D13, X1, X2 all still have PRs in
 * flight against these pages) — exactly the "stop and report a conflict"
 * situation §1 rule 13 describes, done ahead of time instead of after a merge
 * clash. So this dictionary and the `t()` seam below are wired through the
 * two surfaces this workstream *does* own outright — `components/ui/**`
 * (the kit every page composes) and `components/layout/Sidebar.tsx` (the
 * one navigation surface everyone sees) — proving the mechanism end to end,
 * with the rest of the product still genuinely, visibly English: nothing
 * here pretends a string is translated when it is not.
 *
 * `en-AU` is the only locale shipped. An unknown key renders as
 * `⟦missing:key⟧` rather than silently falling back to English prose that
 * looks like a real translation — the same "untranslated must look
 * untranslated" rule the task brief states explicitly. Once a second locale
 * exists, add a sibling dictionary and switch on it in `useI18n`; nothing
 * about the `t()` call sites needs to change.
 */
export const EN_AU = {
  "common.cancel": "Cancel",
  "common.save": "Save",
  "common.saving": "Saving…",
  "common.close": "Close",
  "common.loading": "Loading…",
  "common.retry": "Retry",
  "common.previous": "Previous",
  "common.next": "Next",
  "common.noResults": "No results.",
  "common.optional": "optional",
  "common.dash": "—",

  "nav.gettingStarted": "Getting Started",
  "nav.group.operations": "Operations",
  "nav.overview": "Overview",
  "nav.liveMap": "Live Map",
  "nav.dispatch": "Dispatch",
  "nav.messages": "Messages",
  "nav.duressDesk": "Duress Desk",
  "nav.group.trips": "Trips & Fares",
  "nav.trips": "Trips",
  "nav.shifts": "Shifts & Reconciliation",
  "nav.tariffStudio": "Tariff Studio",
  "nav.zones": "Zones & Demand",
  "nav.pslCentre": "PSL Centre",
  "nav.group.fleet": "Fleet",
  "nav.fleetDrivers": "Fleet & Drivers",
  "nav.complianceVault": "Compliance Vault",
  "nav.group.revenue": "Revenue",
  "nav.billing": "Billing",
  "nav.paymentRecon": "Payment Reconciliation",
  "nav.vouchers": "Vouchers & Accounts",
  "nav.group.engagement": "Driver Engagement",
  "nav.announcements": "Announcements",
  "nav.incentives": "Incentives",
  "nav.driverWallets": "Driver Wallets",
  "nav.ratings": "Ratings",
  "nav.group.admin": "Administration",
  "nav.auditLog": "Audit Log",
  "nav.whiteLabel": "White-label",
  "nav.security": "Security",
  "nav.platformAdmin": "Platform Admin",
  "nav.logout": "Log out",

  "ui.toast.dismiss": "Dismiss",
  "ui.pagination.pageOf": "Page {page} of {total}",
  "ui.table.noResults": "No data",
  "ui.table.loadingAnnouncement": "Loading table data",
  "ui.emptyState.defaultTitle": "Nothing here yet.",
  "ui.themeToggle.light": "Light",
  "ui.themeToggle.dark": "Dark",
  "ui.themeToggle.system": "System",
} as const;

export type I18nKey = keyof typeof EN_AU;
