# agent-quant — rules for Claude

The QuantGen copilot service. Owned by the **backend session** (owner, 2026-10-24). Read the root `CLAUDE.md`
first (its "AI rules" section applies here), then `ARCHITECTURE.md` §12.7 and `DECISIONS.md` D-017…D-023,
D-039, D-044, D-045.

- **Stateless (D-045).** No database. The core sends each turn (`POST /internal/v1/turns`, SSE back) or compile
  request (`POST /internal/v1/compile`) with the shared secret (`X-Agent-Secret`), a delegated token and the history.
  The core stores conversations, runs and steps.
- **Never touches money.** Tools are thin calls to the core's 🤖 endpoints with the delegated token
  (`tools/ToolBox`). The strongest tool is `propose_intent`; the user approves in the app.
- **Provider (D-044):** any OpenAI-compatible `/chat/completions` (`QG_AI_BASE_URL`, `QG_AI_MODEL`, `QG_AI_API_KEY`
  or `QG_GEMINI_API_KEY`). Default: Gemini free tier. A 429 is `AI_BUSY`, never a blind retry. No key → 503.
- **Masking:** `run/Redactor` masks every 10–19 digit number and wraps tool results in `<bank_data>`; step details are
  masked and never contain the token. Never log a token, the secret, the key or message text.
- **Agents:** `run/Agents` (prompts + tool allowlists); the loop and delegation are in `run/AgentRunner`
  (10 tool calls per turn, citations only for ids a tool returned).
- **Run:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (port 8090, loopback; reads the key from the env).
  Tests: `./mvnw test` (fake LLM + fake core, no network, no cost).
