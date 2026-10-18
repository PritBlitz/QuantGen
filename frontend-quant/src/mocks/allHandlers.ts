/**
 * Every MSW handler: v1 (PRD §7) plus v2 (PRD §12). v2 handlers come first so that, where a v2
 * route overlaps a v1 one (orders, transfers), the v2 behaviour wins or falls through.
 */
import { handlers as v1 } from './handlers'
import { analyticsHandlers } from './v2/analytics'
import { autopilotHandlers } from './v2/autopilot'
import { cardHandlers } from './v2/cards'
import { copilotHandlers } from './v2/copilot'
import { paymentHandlers } from './v2/payments'
import { proofHandlers } from './v2/proof'
import { riskHandlers } from './v2/risk'
import { savingsHandlers } from './v2/savings'
import { securityHandlers } from './v2/security'
import { tradingHandlers } from './v2/trading'

export const handlers = [
  ...securityHandlers,
  ...paymentHandlers,
  ...savingsHandlers,
  ...analyticsHandlers,
  ...copilotHandlers,
  ...autopilotHandlers,
  ...riskHandlers,
  ...tradingHandlers,
  ...proofHandlers,
  ...cardHandlers,
  ...v1,
]
