# Backend handoff

History before 2026-10-29 lives in `git log` (and in this file's own history); the PRD "Backend notes" hold every
contract detail. This file is only the current state, what's open, and the cautions that still apply.

## State (2026-10-30, session backend-quant-fe)
- **Every PRD §7/§11/§12 backend item is built**, P2s included (F-TRD-9 is an Autopilot template; F-UX-5 Hindi was
  dropped by the owner). A script audit maps all 184 PRD §7/§12 endpoints to controllers in `backend-quant` or
  `agent-quant`. Added 2026-10-29/30 at the owner's request: real NSE closes in backtests (D-046, `0d43604`), the
  daily NSE sync + one-time live-price rebase (D-047, `dc24786`; rebased on the dev DB 2026-10-30 00:49 IST), the
  payments view with receipt numbers (PRD §12.4, `da19127`, V34), Razorpay live-key refusal (`533beac`), agent drain
  guard fails closed (`2d9dae6`).
- Tests: backend `./mvnw verify` (39 IT classes + unit, Testcontainers, Docker must run); agent-quant `./mvnw test`
  17 (fake LLM, fake core, no cost). Dev DB at **V34**.
- Running: :5433 dev Postgres (`docker-compose.dev.yml`), :8080 API (dev profile, `QG_RATE_LIMIT_ENABLED=false`,
  `-Xmx512m`, the three `QG_RAZORPAY_*` from the root `.env`), :8090 agent-quant (dev profile, Gemini key from the
  root `.env`). Jars, logs and two pre-migration dev DB dumps are in **`backend-quant/target/run/`** (C: was full;
  `mvn clean` deletes them). The frontend session sets all feature flags off after any restart.
- Razorpay test mode is live: a ₹500 test payment credited once via verify. The webhook isn't registered in the
  dashboard yet (owner: tunnel + paste the same `QG_RAZORPAY_WEBHOOK_SECRET` value).
- **Machine limits:** C: had < 300 MB free and RAM ~1–2 GB free; full verifies crashed twice with JVM out-of-memory
  (rerun the crashed class alone; stop :8090 while verifying).

## Open
1. **Live copilot turn through the core: confirmed 2026-10-29** on a50922a (frontend live e2e took the answered
   branch, 3492/208 tokens; it needed the new one-retry-on-503, since Gemini answered 503 mid-turn). Not yet seen
   live: `/autopilot/compile` answering (its run got 503 twice → `AI_BUSY`, the UI fell back to the form).
2. **F-AGT-8 eval gate unmeasured** (red-team 100% safe, golden ≥ 90%). A full run takes over an hour and the day's
   free quota, so run slices: `QG_AI_API_KEY=… ./mvnw test -Dtest=AgentEvals -Dsurefire.failIfNoSpecifiedTests=false
   -Dqg.eval.only=G01,…,G20 -Dqg.eval.pause-ms=8000` (in agent-quant). Fixes from partial runs: the rule-override
   tripwire (R03) and the drain guard (R16).
3. **Owner decision:** a Razorpay capture that arrives after its top-up `EXPIRED` (> 30 min) for an account closed
   since can't be credited (the posting guard refuses; Razorpay keeps retrying the webhook). Refund through the
   Razorpay API, or credit another of the user's accounts? Unpaid (`CREATED`) top-ups already block closing.
4. **Needs the owner:** Razorpay test keys (`QG_RAZORPAY_KEY_ID` / `_KEY_SECRET` / `_WEBHOOK_SECRET`, `razorpay` flag
   on, a tunnel for webhooks, §7.9); anchoring (public repo, `QG_ANCHOR_REPO_DIR` + `QG_ANCHOR_REPO_URL`; the API
   commits, the owner pushes). Crypto prices need egress to api.coingecko.com when its flag is on.
5. Not PRD requirements on the free tier: streaming tokens from the provider (the agent chunks its final text),
   provider-side prompt caching.

## Known limits (by design or accepted)
- Cooling and first-transfer step-up cover **saved payees only** (spec F-SEC-5); an unsaved recipient (by handle,
  or via a payment request) skips both. Scheduled runs skip step-up (`preAuthorized=true`); creation is stepped up.
- Trading: backtests use simulated daily closes; no notification on a limit fill; a fill that fails (e.g. frozen
  account) stays OPEN and retries each tick until it expires.
- Insights: categories are keyword-based (the D-020 classifier isn't used); net worth counts FD principal only;
  salary detection can't be integration-tested (ledger rows can't be backdated).
- Sandbox KYC auto-verifies (D-042).

## Cautions
- `/actuator/prometheus` relies on nginx never proxying `/actuator`; don't add that route.
- Prod needs `QG_FIELD_KEY`, `QG_INDEX_KEY`, `QG_WEBAUTHN_RP_ID` (default `localhost`). `QG_INDEX_KEY` also derives
  proof salts and card PAN/CVV lookups: rotating it breaks card lookups (stored salts keep old proofs verifiable).
- MCP is `http://127.0.0.1:8090/mcp`, PAT bearer only; exposing it beyond loopback is the infra session's call.
- Shared git index with the frontend: commit with explicit paths. `Setup.md` has an uncommitted owner edit; leave it.
- `mvn` doesn't clean `target/`: delete stale files in `target/classes` if a renamed migration clashes. Don't compile
  while a `verify` runs.
- Bash heredocs containing `'''` break in this harness: put Python scripts in files.

## Routine
build → targeted ITs → full `verify` → commit with explicit paths → jar from `git worktree add --detach
<scratch>/wt HEAD` (keeps uncommitted work off :8080) → for a new migration, smoke it on a `pg_dump` clone run on
:8099 first → swap :8080 → PRD "Backend notes" + message the frontend (`ListAgents` → `SendMessage`).
