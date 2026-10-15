# Frontend Session Rules — `frontend-quant/`

Read `../CLAUDE.md` (security and money rules), `../PRD.md` (§7 API contract, §9 pages) and
`DESIGN.md` (the visual and UX spec) before building any screen.

## Architecture
```
src/
├── app/          router, providers, layouts (AppShell, AuthLayout, LandingLayout)
├── api/          axios client, interceptors, typed endpoint functions per PRD §7, error mapping
├── features/     auth, dashboard, accounts, transfers, markets, portfolio, loans, notifications, admin
│                 (each: components/, hooks/ [TanStack Query], schemas.ts [zod], pages)
├── components/   ui/ (shadcn), shared building blocks (Money, MaskedAccount, StatTile, ...)
├── lib/          money.ts (decimal + INR format), motion presets, idempotency, utils
├── mocks/        MSW handlers that return PRD §7 shapes exactly
└── styles/       tokens.css, globals.css
```

## Rules
- **The API layer mirrors PRD §7 exactly.** Types go in `src/api/types.ts`. When the backend
  is live, check them against `/v3/api-docs`. Never invent fields.
- **The token lives in memory only** (a Zustand store, not persisted). On load, call
  `/auth/refresh` to restore the session. A 401 triggers **one** refresh attempt (concurrent
  401s share a single in-flight refresh), then logout.
- Axios uses `withCredentials` for `/auth/*`. In dev, the Vite proxy maps `/api` and `/ws`
  to `http://localhost:8080`.
- **Money:** render with the `<Money>` component and calculate with `lib/money.ts` (decimal).
  Never use `Number()` or `parseFloat` on amounts.
- **Idempotency:** `useIdempotencyKey()` creates a UUID per review step and resets it only
  after success or when the user edits the form.
- **Mocks:** MSW is on when `VITE_USE_MOCKS=true`. Switch each feature to the real API as the
  backend announces its endpoints are live.
- No `dangerouslySetInnerHTML`. Don't put tokens, account numbers or PII in URLs,
  `console.log` or error reports.
- **Quality:** TypeScript strict with no `any`; ESLint plus Prettier; component tests for money
  forms; Playwright end-to-end tests for login → transfer → buy. Check every screen at
  360px, 768px and 1440px, in both themes and with reduced motion.

## Commands
```bash
npm run dev        # http://localhost:5173
npm run build
npm run test       # vitest
npm run e2e        # playwright
npm run lint
```
