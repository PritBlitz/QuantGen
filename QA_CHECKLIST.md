# QuantGen QA checklist

A walk-through of every v1 feature: what to do, an example input, and what you should see.
Tick the boxes as you go. Section 13 lists what QuantGen deliberately **doesn't** do.

**Rule for every money action:** fill in → **Review** → **Confirm**. Balances change only after
the server confirms, never before.

---

## 0. Setup

| Mode | Start | Open |
|---|---|---|
| Full stack (recommended) | `docker compose up -d --build` (see README) | http://localhost:8088 |
| Dev | Postgres + backend on :8080 + `npm run dev` | http://localhost:5173 |
| Frontend only (mock data) | `cd frontend-quant && npm run dev:mock` | http://localhost:5173 |

**Logins** (demo data):

| Role | Email | Password |
|---|---|---|
| Customer | `demo@quantgen.dev` | `Demo@1234` |
| Admin | `admin@quantgen.dev` | `Admin@1234` |

> If the dev terminal shows `http proxy error … ECONNREFUSED`, the backend on :8080 isn't
> running. Start it, then reload.

Tip: to test "another person", register a second customer in a private window.

---

## 1. Landing page (`/`)

- [ ] Opens without logging in. Large headline, a scrolling price ticker, feature tiles, and a "ledger" scroll section.
- [ ] **Sign in** goes to `/login`; **Open account** goes to `/register`; **Try the demo** also goes to `/login` (then use the demo login).
- [ ] Looks right on a phone (narrow the browser to ~360 px): no sideways scrolling.

## 2. Register, login, session

**Register (`/register`)**
- [ ] Weak password `password` → inline error; the strength meter stays low.
- [ ] Rule: **8+ characters with an uppercase letter, a lowercase letter, a digit and a symbol**. Example: `Tester@123`.
- [ ] A space is **not** a symbol: `Tester 123` → the rules list keeps "A symbol" unticked, and the form won't submit.
- [ ] Phone `+91 98123 45678` (with spaces) is accepted; spaces and dashes are removed before sending.
- [ ] Confirm password that doesn't match → inline error.
- [ ] Email that already exists (`demo@quantgen.dev`) → "is already registered" under Email.
- [ ] Valid registration (name `Test User`, email `test1@example.com`, phone `+919812345678`) → you land on the **Dashboard**, signed in.
- [ ] The new user automatically has **one Savings account with ₹0.00**.

**Login (`/login`)**
- [ ] Wrong password → "That email and password don't match…". The message is the same for an unknown email, so the app never reveals which emails exist.
- [ ] **5 wrong passwords within 15 minutes** → login is locked for 15 minutes ("Too many attempts. Try again in N minutes").
- [ ] More than **10 login attempts per minute** from one machine → "Too many attempts" (rate limit).
- [ ] Customer lands on **/dashboard**; admin lands on **/admin**.
- [ ] "Use demo account" button: present **only** in dev or mock mode, absent on :8088.

**Session**
- [ ] Reload the page while signed in → you stay signed in (the session restores silently).
- [ ] Open `/accounts` while signed out → sent to login; after signing in you return to `/accounts`.
- [ ] Leave the tab idle for **10 minutes** → a "Still there?" dialog with a **60-second countdown** appears. **Stay signed in** keeps you in; doing nothing signs you out.
- [ ] Account menu (your initials, top right) → **Sign out** returns you to login. **Back** does not get you back in.

## 3. Dashboard (`/dashboard`)

- [ ] A greeting with your name, then tiles for **net worth**, **accounts**, **portfolio** (with a small chart), **recent activity** and **loan progress** (a ring), plus quick actions.
- [ ] Quick actions **Transfer / Add money / Buy shares / Get a loan** each open the right screen.
- [ ] **Ctrl + K** (or "Quick actions" in the sidebar) opens the command menu. Type `orders` and press Enter to go to Orders.
- [ ] The sun/moon icon switches between **light and dark** themes, and the choice is remembered.

## 4. Accounts (`/accounts`, `/accounts/:id`)

- [ ] Each card shows the type, a **masked number** `•••• •••• 1234`, the balance and its status.
- [ ] The eye icon reveals the full 12-digit number, which **hides again after 10 seconds**.
- [ ] **Open account** → choose Savings or Checking → a new card appears with ₹0.00.
- [ ] Up to **3 accounts** per customer; after that, **Open account** is disabled.

**Add money (demo deposit)**
- [ ] **Add money** → `5000` → **Review** shows "Balance after" → **Add ₹5,000.00** → a tick → the balance rises by exactly ₹5,000.00.
- [ ] `100001` → error: the maximum is **₹1,00,000 per deposit**.
- [ ] `0.5` or `abc` → error; the minimum is ₹1 and at most 2 decimals are allowed.
- [ ] Double-click **Add** → the money is added **once** (duplicate-request protection).

**Account detail**
- [ ] The transaction list is newest first, with credits in green **+₹** and debits in **−₹**, and the balance after each row.
- [ ] Filter by **type** (Deposit, Transfer, Trade, Loan disbursal, Loan repayment) and by a **date range** → the list updates.
- [ ] **Download statement** → choose dates → a CSV downloads, named `quantgen-statement-1234-<from>-to-<to>.csv` (last 4 digits only).
- [ ] Statement: an end date in the future, or a range over 366 days → inline error.

## 5. Transfers (`/transfer`)

Recipient tabs: **Saved / My accounts / New**.

- [ ] **My accounts**: send `1500` from Savings to Checking → Review → **Send ₹1,500.00** → "Transfer completed". Savings falls by ₹1,500 and Checking rises by ₹1,500.
- [ ] **New**: enter another customer's 12-digit number → the review shows their **name**.
- [ ] Unknown account number `000000000000` → an inline error, "no account with this number" (mock mode says "account not found").
- [ ] Your own source account as the recipient → "must be different from the source account".
- [ ] Amount `0.50` → error (minimum **₹1**).
- [ ] Amount `200001` → error (maximum **₹2,00,000 per transfer**).
- [ ] More than **₹5,00,000 to other people in one day** → "You can send up to ₹X more today". Moves between your own accounts don't count.
- [ ] More than the balance → "You need ₹X more", with an **Add money** shortcut.
- [ ] Double-click **Send** → the button shows "Sending…" and is disabled; **only one** transfer happens.
- [ ] The recipient sees a **"Money received"** notification.

**Saved recipients (beneficiaries)**
- [ ] Add a nickname plus an account number → it's checked, and the holder's name is shown.
- [ ] Adding your own account, an unknown number, or a duplicate → error.
- [ ] **Send to [nickname]** pre-fills a transfer; **Remove** deletes the recipient.

## 6. Markets and trading

**Markets (`/markets`)**
- [ ] About **15 instruments** (QGEN, INFY, TCS, RELI, HDFC…), each with a price, a % change and a small chart.
- [ ] The badge shows **Live**, and prices **change every ~3 seconds** with a brief flash.
- [ ] The sector filter (IT Services, Financials, …) narrows the list.

**Instrument (`/markets/QGEN`)**
- [ ] A large price chart with **1H / 1D / 1W** tabs; the 1H chart moves live.
- [ ] Order ticket (right side on desktop; a **Buy** button opens a bottom sheet on a phone):
  - [ ] Buy quantity `2` → **Review buy** shows "Estimated cost" → **Confirm buy** → "Order filled · Bought 2 QGEN". Cash falls by **2 × the fill price**.
  - [ ] Quantity `0`, `1.5` or `10001` → error (**whole numbers from 1 to 10,000**).
  - [ ] **Sell** more shares than you hold → "You don't hold enough shares to sell that many".
  - [ ] Buy for more than your cash → "You need ₹X more".

**Portfolio (`/portfolio`)**
- [ ] An allocation donut and a holdings table (quantity, average cost, price, value, **unrealized P&L** with ▲ or ▼ and a colour).
- [ ] **Realized P&L** changes after you sell for more or less than your average cost.

**Orders (`/orders`)**
- [ ] Every order, newest first: side, quantity × symbol, fill price, total, and status (Filled or Rejected). It pages after 20.

## 7. Loans (`/loans`, `/loans/:id`)

Fixed **12% a year**. Amount **₹10,000 to ₹10,00,000**; tenure **6 to 60 months**.

**EMI calculator**
- [ ] Drag the sliders → the EMI, total interest and the chart update live. Example: ₹2,00,000 over 24 months is about ₹9,414.69 a month.

**Apply** (**Apply for a loan** → 3 steps → review)
- [ ] Amount `20000`, income `50000`, purpose `Laptop`, payout account → **Submit application** → "Application submitted". The status shows **In review**.
- [ ] Amount `5000` or `2000000` → error.
- [ ] **Not eligible** when the new EMI plus existing EMIs is **over 50% of income**, or the amount is **over 20× income**. Example: income `10000` with amount `300000` → "not eligible".
- [ ] Pending applications count as existing EMIs, so a second large application can be refused.

**After an admin approves** (see §9)
- [ ] The status becomes **Active**, the money lands in the payout account (a "Loan disbursal" credit), and a **"Loan approved"** notification appears.
- [ ] The loan detail shows the **schedule**: instalment no., due date (the first one a month after approval), EMI, principal and interest split, and status (Upcoming, Paid or Overdue).

**Repay** (loan detail → Repay panel)
- [ ] **Next EMI** → Review → **Pay ₹X** → "Repayment confirmed". That row becomes **Paid** and the account falls by exactly ₹X.
- [ ] **Custom amount** less than one EMI (e.g. `5000`) → accepted as a **partial** payment. The row shows "₹X left", and the next "Next EMI" charges exactly that remainder.
- [ ] Custom amount above everything still owed → "The most you can pay is ₹X".
- [ ] A notification titled **"EMI paid"** (or **"Loan closed"** for the final one) appears.
- [ ] Paying everything off → the status becomes **Closed**.
- [ ] Repaying from a **frozen** account → "This account is frozen".

**Automatic (background)**
- [ ] A daily job at **06:00 IST** sends "EMI due" 3 days before each due date, auto-debits on the due date, and marks the instalment **Overdue** (with a notification) if the balance is too low.

## 8. Notifications (bell icon, `/notifications`)

- [ ] The bell shows an **unread count** (9+ when there are more than 9).
- [ ] The list is grouped into **Today** and **Earlier**, newest first, with an icon per type: money received, loan approved or rejected, EMI due, paid or overdue, account frozen or unfrozen.
- [ ] **Unread** filter → shows only unread items.
- [ ] **Mark read** on one item (hover on desktop) → its dot disappears and the count drops by 1.
- [ ] Clicking an item → marks it read and opens the related screen (Accounts or Loans).
- [ ] **Mark all as read** → "You're all caught up.", and the bell count clears.

## 9. Admin console (log in as `admin@quantgen.dev`)

The admin sees a different menu: **Overview, Users, Loan queue, Audit log, Reconcile**.

**Overview (`/admin`)**
- [ ] Tiles: **total users** (customers only), **total deposits**, **active loans**, **outstanding principal**, **trades today** (IST day).
- [ ] A loan-queue preview with "N pending", and the latest audit entries.

**Users (`/admin/users`)**
- [ ] Search by part of a **name, email or phone** (e.g. `demo`) → the list filters as you type.
- [ ] Open a user → their profile, **accounts** with balances, and **loans**.
- [ ] **Freeze** an account → a reason is required (**5 to 500 characters**; `abc` is too short) → **Freeze account**. The status shows **Frozen**.
  - [ ] As that customer: **sending, trading and repaying fail** with "This account is frozen", but **receiving money still works**.
  - [ ] The customer gets an **"Account frozen"** notification that includes the reason.
- [ ] **Unfreeze** with a reason → **Active** again, and an "Account unfrozen" notification is sent.

**Loan queue (`/admin/loans`)**
- [ ] Filters: Pending (the default), Approved, Active, Rejected, Closed, All.
- [ ] Each card shows the applicant, amount, tenure, EMI, income, **EMI / income %** (red over 50%) and the purpose.
- [ ] **Approve** (note optional) → the dialog warns that the money moves now → **Approve and disburse**. The loan disappears from Pending, and the customer is paid and notified.
- [ ] **Reject** → a reason is required → the customer sees "Loan application declined" with the reason.
- [ ] Double-click **Approve** → it's sent only once.
- [ ] If another admin already decided → "This has already changed" (no double decision).
- [ ] An admin can't decide **their own** application.

**Audit log (`/admin/audit`)**
- [ ] Shows who did what, and when: logins, failed logins, lockouts, logout, registration, large transfers (₹1,00,000 or more), freeze/unfreeze, loan applied, approved and rejected.
- [ ] Each entry has the time, action, actor email, target, details, **IP address** and request id.
- [ ] Filter by **actor email** (part of it, e.g. `admin@`) and by **action**.

**Reconcile (`/admin/reconcile`)**
- [ ] **Run reconciliation** → "**Balanced**", ledger sum ₹0.00, 0 mismatches. This proves no money was created or lost.

## 10. Security checks (try to break it)

- [ ] As a customer, open `/admin/users` → an ordinary **404 "Nothing here."** page; admin pages are never shown.
- [ ] As customer A, paste customer B's account or loan URL (`/accounts/<B's id>`) → **not found**, never B's data.
- [ ] DevTools → Application → Local/Session Storage: **no token** is stored there (only the theme preference).
- [ ] DevTools → Cookies: `qg_refresh` is **HttpOnly**, **Secure** and **SameSite=Strict**, with path `/api/v1/auth`.
- [ ] Full account numbers never appear in the URL, the page title or a downloaded file name.
- [ ] Errors show friendly text (a toast, or a message under the field), never a stack trace or SQL.

## 11. Look and feel

- [ ] Every screen in **light and dark** themes.
- [ ] Every screen at **360 px** (phone), **768 px** (tablet) and **1440 px** (desktop). Below desktop width, the sidebar becomes a **bottom tab bar**.
- [ ] With the OS **Reduce motion** setting on → animations become simple fades.
- [ ] Keyboard only (Tab / Shift+Tab / Enter / Esc): everything is reachable, and a visible focus ring is always shown.

## 12. Automated checks (for developers)

| Command (in `frontend-quant`) | Covers |
|---|---|
| `npm run test` | 76 unit and component tests (money maths, forms, flows) |
| `npm run e2e` | Browser tests on desktop and mobile, plus the WCAG 2.1 AA audit of every screen |
| `QG_LIVE=1 QG_BASE_URL=http://localhost:8088 npx playwright test e2e/live.spec.ts --project=desktop` | The real UI against the real stack (sections 2–9) |
| `cd backend-quant && ./mvnw verify` | 103 backend tests, including 100 parallel transfers |

## 13. What QuantGen does **not** do (v1 by design)

| Not available | Why / instead |
|---|---|
| Real money, bank rails (UPI or NEFT), a real broker, or real market data | Simulated: "Add money" is demo funding, and prices are a random walk |
| Currencies other than INR | INR only |
| Mobile apps | The responsive website works on phones |
| Limit or stop orders, fractional shares, short selling | Market orders only, in whole shares |
| Editing your profile, changing your password, "Forgot password" | Not in v1. The profile is view-only (theme choice and sign out only) |
| Email or SMS alerts | In-app notifications only |
| Closing an account | The "Closed" status exists, but there's no button to close one |
| PDF statements | CSV only (PDF is a stretch goal) |
| Undoing a transaction from the UI | The ledger is append-only. Corrections are reversing entries done by the backend, not via a screen |
| Variable interest rates, prepayment penalties | Fixed 12% a year; repayments are capped at what's owed |
| Deleting users, creating admins from the UI | Admins are seeded |
| AI copilot, crypto, passkeys, hash-chained ledger | Planned for **v2** (PRD §11) |

## 14. V2-M3 savings and insights (demo script)

Use a fresh customer. Add ₹1,00,000 to the savings account first.

| # | Do this | Expect |
|---|---|---|
| 1 | Create a goal "Goa trip", target ₹25,000, a date 6 months out, an emoji | It appears with 0% progress; your 3-account cap is untouched |
| 2 | Move ₹5,000 from savings to the goal, then ₹1,000 back | Goal shows ₹4,000; try paying someone else *from* the goal: refused |
| 3 | Ask a second user to pay into your goal's account number | They get "no account with this number", exactly as for a made-up number |
| 4 | Turn round-ups on for savings (round to ₹10, into the goal); pay someone ₹123.40 | Round-up of ₹6.60 is moved to the goal at 23:30 IST as one "Round-up savings" transfer |
| 5 | Book an FD of ₹50,000 for 365 days, pay out at maturity | 7.00% p.a.; maturity amount ₹53,500.00; savings drops by ₹50,000; a new FD account appears and can't send or receive |
| 6 | Close the FD early | Asks for step-up (PIN); within the first 7 days it pays back exactly ₹50,000 (no interest) |
| 7 | Pay someone with notes "Lunch at the cafe" and "Rent for October", and pay a bill | Insights show FOOD, RENT and BILLS; transactions and transfers each carry a category |
| 8 | Recategorize a payment, then choose "always for this person" | The category sticks for past and future payments to that person, for you only |
| 9 | Set a FOOD budget just above what you spent, then spend past it | A BUDGET_ALERT at 80% and another at 100%, once each this month |
| 10 | Open net worth and the 30-day forecast with a scheduled transfer set up | Net worth = cash + goals + FDs + portfolio − loans; the forecast dips on the scheduled date and warns below ₹1,000 |

Interest is checked to the paisa in `FixedDepositsAndInterestIT` (FDs: principal × rate × days / 36500; savings: 3%
on the daily closing balance, credited on the 1st).

## 15. V2-M8 Proof of Balance (demo script)

| # | Do this | Expect |
|---|---|---|
| 1 | Signed out, open `GET /api/v1/public/proofs/roots` (or the landing page's live root) | Today's root, total liabilities (4 decimals), leaf count and chain head; no login needed |
| 2 | Log in as `demo@quantgen.dev`, open Verify my balance for each account | ✅ for every account; the leaf balance equals the account balance at snapshot time |
| 3 | Open an account created after today's snapshot | "Not in that snapshot" (404), never an error page |
| 4 | Ask for another customer's account id | 404, exactly as for a made-up id |
| 5 | As admin, run chain verification | `intact: true`, `checkedUpTo` = the chain head; an audit row `LEDGER_CHAIN_VERIFIED` |
| 6 | Admin solvency view | Liabilities = sum of customer balances; ratio = assets / liabilities |
| 7 | (Dev DB only, then restore) disable `ledger_lines_immutable`, edit one sealed line's counterparty, verify again | `intact: false` with that line's seal position; restore the value and re-enable the trigger → intact |

Automated: `ProofOfBalanceIT` (gap-free chain under concurrent sealers, tamper detection of a line and of a seal,
admin alert, every proof recomputed to the published root) and `ProofHashingTest` (vectors from `sha256sum`).
Live proofs were also checked with the frontend's own `src/lib/merkle.ts` against a clone of the dev DB.
