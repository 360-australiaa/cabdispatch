# NSW Delivery Control Register

**Program:** Cab Dispatch NSW compliance and market readiness  
**Source plan:** `2026-09-12-nsw-compliance-and-market-readiness-master-plan.md`  
**Status date:** 12 September 2026  
**Baseline under assessment:** `android/battery-network-heartbeat-and-map-fixes` at `5c332f49693b70bacd66faa42cecb438c8cee9da`

This register is the delivery-control record required by the master plan. A work item is not closed until its tests, migration or rollback evidence, operational evidence, and accountable acceptance are recorded here.

| ID | Source finding/control | Severity | Owner | Code locations | Tests | Migration / evidence | Target release | Status | Accepted by |
|---|---|---|---|---|---|---|---|---|---|
| P0-001 | One authoritative integration branch and reproducible release baseline | Blocker | Engineering lead | Git branches/worktrees; release automation | Clean-checkout gates pending | Current worktree is `android/battery-network-heartbeat-and-map-fixes` at `5c332f4`. Candidate integration branch `phase0/merge-to-main` is at `0b4b64f`. Inventory contains many active divergent worktrees; none may be deleted or merged without recorded disposition. | Baseline | In progress | — |
| P0-002 | Deterministic backend, dashboard and Android gates | Blocker | QA / platform | `backend`, `dashboard`, `android` | Dashboard `npm run lint` passed on 2026-09-12. Backend suite is blocked: checked-in `.venv` targets inaccessible Python `3.14`; Android `gradlew test` reached compilation but failed from competing Gradle/Kotlin daemons, memory exhaustion and a locked generated resource. | Record host OS, JDK, Python and Node versions; make cache location, worker count and daemon policy explicit in CI; add CI results and remediation for backend test environment. | Baseline | In progress | — |
| P0-003 | Audit traceability and evidence integrity | High | Product owner | `docs/audits`, `docs/plans` | Documentation review | Master plan references `docs/audits/2026-09-11-full-stack-audit.md`; it is absent. Existing audits are dated 2026-09-08. Do not claim closure against the missing audit until it is restored or the plan is corrected with an approved traceability mapping. | Baseline | Open | — |
| P1-001 | Non-negative monetary/quantity validation and independent 5% non-cash cap | Blocker | Backend fare owner | `backend/app/schemas/trips.py`; backend and Android fare kernels | Backend schema and golden-vector tests; Android fare-engine test | Schema validation and kernel-level zero lower bounds implemented for tolls, extras, cleaning fee, surcharge percentage and offline device total. API boundary suite and signed cleaning-fee-cap verification remain. | NSW pilot | In progress | — |
| P1-002 | Immutable closed financial records and idempotent payment/voucher/PSL actions | Blocker | Backend payments owner | Trip, shift, payment, voucher and PSL services/models | Concurrency, replay and correction tests pending | Needs compensating-record design and rollback/migration evidence. | NSW pilot | Open | — |
| P1-003 | Legal meter states including `STOPPED` and offline replay | Blocker | Android and backend fare owners | Android meter/Room/sync; OpenAPI; backend trip/fare models | State-machine, GPS blackout, duplicate/out-of-order and golden-vector tests pending | Cross-stack schema migration required; passenger-visible reason and append-only transition journal required. | NSW pilot | Open | — |
| P2-001 | Tenant/role authorization for duress, messages and billing | Blocker | Security owner | Backend route dependencies and dashboard capabilities | Ownership/role matrix tests pending | Every decision must be auditable. | NSW pilot | Open | — |
| P2-002 | Durable duress escalation and five-second reporting | Blocker | Safety/platform owner | Duress worker, queue/Redis, Android service | Failure-mode drill tests pending | Requires chosen monitoring facility and response procedure before operational acceptance. | NSW pilot | Open | — |

## Baseline decisions pending

1. Approve or reject `phase0/merge-to-main` as the integration candidate after its clean-checkout gates and worktree disposition are reviewed.
2. Restore the missing 11 September audit or approve a replacement traceability mapping.
3. Provide a usable, supported Python test runtime for backend CI; the checked-in virtual environment is not portable on this host.
4. Name accountable owners for fare/legal, payments, safety/duress, security, and release acceptance.

## Evidence log

| Timestamp (local) | Check | Result | Notes |
|---|---|---|---|
| 2026-09-12 | `git status --short` | Recorded | Untracked plan and `.claude/settings.local.json` were present before program changes. |
| 2026-09-12 | `git worktree list --porcelain` | Recorded | Multiple active worktrees and branches found; preservation required. |
| 2026-09-12 | Dashboard `npm run lint` | Pass | Runs `tsc --noEmit`. |
| 2026-09-12 | Backend `.venv\\Scripts\\python.exe -m pytest -q` | Blocked | Virtual environment references an inaccessible Python executable outside the repository. |
| 2026-09-12 | Android `gradlew test` with workspace `GRADLE_USER_HOME` | Blocked | The pinned Gradle distribution downloaded, then concurrent Gradle/Kotlin daemon work exhausted host memory and locked a generated release resource. Re-run in a single-worker, no-daemon CI job. |
| 2026-09-12 | USB device readiness | Ready | Authorized Samsung SM-T575 (Android 13) is visible through ADB. Cab Dispatch package is not installed; an existing debug APK is available but predates the current fare-kernel change and must not be used as proof of this change. |
| 2026-09-12 | Physical tablet smoke test | Blocked | Installed and launched existing Cab Dispatch debug APK v0.6.2 (11) on SM-T575. Camera, precise foreground location, microphone and notifications were granted for testing; the disclaimer was accepted as the user-authorized test operator. No startup crash observed. Commissioning is blocked by a required pairing code. |
| 2026-09-12 | Tablet-to-backend connectivity | Failed | App logs show `SocketTimeoutException` connecting to `72.61.107.107:8001` from the tablet. The commissioning screen consequently has no signed tariff, vehicle class or depot heartbeat. Treat this as a production-readiness blocker until the device network path and configured API origin are verified. |
| P2-003 | Operator-facing error handling must not disclose network topology | High | Android security owner | `android/.../DevicePairingRepository.kt` | Unit test for socket-timeout redaction added; physical-tablet regression pending | Pairing transport failures now render a safe actionable message; diagnostics remain in protected logs. The API routing/firewall failure is tracked separately. | NSW pilot | In progress | — |
