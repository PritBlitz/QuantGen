# Frontend → Backend handoff

## 2026-10-16

Messages sent to `backend-quant-15`; it showed "waiting" the whole time, so they may not have
been read yet. This file is the durable copy.

**Contract (PRD §7.7/§7.8, commits f5cdad0 and 6225140).** Additive, frontend built against it:
- `AdminUserDto`, `AdminUserDetailDto`, `AdminLoanDto`, `StatsDto`, `AuditLogDto` as written in §7.7.
- `ReconcileDto` now matches the already-implemented endpoint: mismatches are
  `{ledgerAccountId, code, cachedBalance, computedBalance}`.
- `NotificationDto = {id, type, title, body, read, createdAt}` (type = existing `NotificationType`
  enum), list newest first and capped at 50, and another user's notification returns 404.
- **New:** `POST /notifications/read-all` → `204`.
- Approve/reject on a non-PENDING loan → `409 INVALID_STATE`. Freeze/unfreeze and loan
  decisions are audited and notify the customer.

**Audit action names the UI knows:** existing `USER_REGISTERED, LOGIN_SUCCESS, LOGIN_FAILED,
LOGIN_LOCKED, LOGIN_BLOCKED, LOGOUT, REFRESH_TOKEN_REUSE, LARGE_TRANSFER`; please use
`ACCOUNT_FROZEN`, `ACCOUNT_UNFROZEN` (targetType `ACCOUNT`, details `Account ••••1234: <reason>`),
`LOAN_APPROVED`, `LOAN_REJECTED` (targetType `LOAN`, details = reason) for M5.

**M6 needs from backend:**
1. `backend-quant/Dockerfile`: multi-stage, JRE 25 runtime, non-root, `wget` available (the
   compose healthcheck runs `wget -qO- http://127.0.0.1:8080/actuator/health`).
2. `application-prod.yml` reading `QG_DB_URL/USER/PASSWORD`, `QG_JWT_SECRET`, `QG_CORS_ORIGINS`
   (today these exist only in the dev profile, so `SPRING_PROFILES_ACTIVE=prod` can't start);
   `server.forward-headers-strategy` so audit IPs aren't the nginx IP; and the name of the
   demo-seed profile for the live demo.

**Verified live against backend M1–M3:** the OpenAPI DTOs match `src/api/types.ts` field for
field; `e2e/live.spec.ts` (`QG_LIVE=1`) passes register → deposit → transfer → buy; the JWT
carries `role`.

**Still mocked (flip in `src/mocks/live.ts` when announced):** `/loans` (M4),
`/admin/*` except reconcile, and `/notifications` (M5).
