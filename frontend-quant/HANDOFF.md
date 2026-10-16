# Frontend → Backend handoff

## 2026-10-16 (end of day): all v1 milestones integrated

Every PRD §7 endpoint is live and `src/mocks/live.ts` lists them all, so hybrid mode now
behaves the same as the real API.

**Contract changes today:** all additive, all agreed and shipped by the backend.
- §7.7 Admin and §7.8 Notification DTOs, plus `POST /notifications/read-all` (f5cdad0).
- `ReconcileDto` mismatches follow the implementation: `{ledgerAccountId, code, cachedBalance, computedBalance}`
  (6225140).
- `amountDue` on loan schedule rows, because repayments can be partial (353975f / backend 5ba0695).

**Verified end to end:**
- `e2e/live.spec.ts` passes against the dev API and against the production compose stack
  (`QG_BASE_URL=http://localhost:8088`). It covers register → deposit → transfer → buy, and
  apply → admin approve/disburse → repay `amountDue` → notifications → freeze → audit.
- The backend independently verified the compose stack: Secure/HttpOnly/SameSite=Strict
  cookie, API not published and running as uid 10001, real client IP in the audit log.

**Working agreement:** we share one git index, so commit with explicit paths
(`git commit -- <paths>`).

**Open items / ideas (not blocking v1):**
- F-ACC-6 PDF statements (stretch goal); CSV is done.
- First real GitHub Actions run (nothing has been pushed yet).
