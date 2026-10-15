# QuantGen — Engineering Rules for Claude

QuantGen is a **banking and investment platform that moves money**. Treat every change as if
real customers' savings depended on it. Correctness and security come before speed and
before cleverness. These rules apply to every session working in this repo.

## Repo map and ownership
| Path | What | Owner session |
|---|---|---|
| `PRD.md` | Scope + **API contract (§7), the source of truth** | Shared; contract edits follow the protocol below |
| `ARCHITECTURE.md` | How it's built: modules, ledger design, auth flow, data model, ADRs | Shared; update it when a design decision changes |
| `docker-compose.dev.yml`, `.env.example` | Dev Postgres (port 5433), env var names | Shared |
| `backend-quant/` | Spring Boot API (see `backend-quant/Setup.md`) | Backend session only |
| `frontend-quant/` | React app (see `frontend-quant/CLAUDE.md`, `DESIGN.md`) | Frontend session only |
| root `docker-compose.yml`, `.github/`, infra | Integration / deploy | Frontend session, from M6 onward |

**Never edit another session's folder.** If you need something there, message that session.

## Think before you code (required for every feature)
Before writing a feature that touches auth, money, or personal data, write down
(in your reply or the commit body) brief answers to:
1. **Who may do this?** Which role, and which ownership check makes sure user A can't act on user B's data?
2. **What can an attacker send?** Malformed, negative, huge, or duplicate input; replayed requests; another user's ids.
3. **What happens under concurrency?** Two requests at once, a double-click, a retry after a timeout.
4. **What happens on failure halfway?** Is it all-or-nothing? What does the user see?
5. **What gets logged or audited?** And what must *never* be logged?

If an answer is "not sure", stop and resolve it first, by reading the PRD, asking the user,
or asking the other session. Don't guess on security or money.

## Money rules (non-negotiable)
- Only `LedgerService.post(...)` changes balances: double-entry, balanced, in one DB transaction, with rows locked in ascending id order.
- `BigDecimal` / `NUMERIC(19,4)` on the backend; **strings** in JSON; on the frontend, never do arithmetic on money with JS `number`. Display only, using formatted strings.
- Every money-moving request carries an `Idempotency-Key`. Retries reuse the same key.
- Ledger rows are insert-only. Fix mistakes with reversing entries.
- Never show an optimistic balance update. The UI waits for the server's confirmation.

## Security rules
- **Secrets:** never commit them. Read config from env vars, and commit `.env.example` (never a real `.env`). Dev defaults live only in dev profiles.
- **AuthN:** access JWT for 15 minutes, **held only in memory on the client** (never in localStorage or sessionStorage). Refresh token goes in an `HttpOnly; Secure; SameSite=Strict` cookie, rotated on every refresh, with reuse detection.
- **AuthZ:** deny by default. Check ownership in the service layer. Another user's resource returns `404`, not `403`, so the response doesn't reveal that it exists.
- **Input:** validate on the server always (Bean Validation), and on the client for UX (zod). Never trust client-side checks.
- **Output:** no stack traces, SQL, or internal ids beyond what the contract exposes. One standard error body (PRD §7.1).
- **Injection and XSS:** parameterized queries / JPA only, no string-built SQL. No `dangerouslySetInnerHTML`. No `eval`.
- **Logs:** never log passwords, tokens, full account numbers, OTPs, or request bodies of auth endpoints. Mask account numbers (`••••1234`).
- **Rate limiting** on auth endpoints, and an **audit log** for sensitive actions (login, freeze, loan decision, large transfer).
- **Dependencies:** prefer well-known, maintained libraries. Don't add a dependency for something trivial. Pin versions.
- If you notice a vulnerability anywhere, even outside your task, flag it to the user immediately.

## Quality bar
- Every feature ships with tests. Money paths need integration tests, including concurrency and idempotency.
- Code matches the contract in PRD §7 exactly: paths, field names, enums, status codes.
- Small, focused commits: `backend: …` / `frontend: …` / `infra: …`. Commit only your own folder. **Never push**; the user pushes.
- Don't mark a milestone done until its tests pass and you have actually run it.

## Contract-change protocol
1. Edit PRD §7 first, keeping the change minimal and noting it in the relevant table.
2. Message the other session (via `ListAgents` → `SendMessage`) saying what changed and why.
3. Then implement. If messaging fails, write a dated note in `<your-folder>/HANDOFF.md` and tell the user.

## Environment
Windows 11. Shells: PowerShell and Git Bash. JDK 25 (Temurin) at `C:\Users\ASUS\.jdks\temurin-25.0.4.1`
(`JAVA_HOME`). Node 24, Docker available. Backend on `:8080`, frontend on `:5173`, Postgres 17 (Docker, `docker-compose.dev.yml`) on
**`:5433`**. A native PostgreSQL 18 Windows service owns `:5432`; it is not ours, so leave it alone.
Architecture details: [`ARCHITECTURE.md`](ARCHITECTURE.md).
