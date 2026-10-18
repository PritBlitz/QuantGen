/**
 * Every MSW handler: v1 (PRD §7) plus v2 (PRD §12). v2 handlers come first so that, where a v2
 * route overlaps a v1 one, the v2 behaviour wins.
 */
import { handlers as v1 } from './handlers'
import { analyticsHandlers } from './v2/analytics'
import { paymentHandlers } from './v2/payments'
import { savingsHandlers } from './v2/savings'
import { securityHandlers } from './v2/security'

export const handlers = [...securityHandlers, ...paymentHandlers, ...savingsHandlers, ...analyticsHandlers, ...v1]
