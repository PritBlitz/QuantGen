import type { AgentName, CitationDto } from '@/api/types'

export const AGENT_LABEL: Record<AgentName, string> = {
  CONCIERGE: 'Concierge',
  LEDGER_ANALYST: 'Ledger analyst',
  PAYMENTS: 'Payments',
  MARKETS: 'Markets',
  PLANNER: 'Planner',
  LOAN_ADVISOR: 'Loan advisor',
  FRAUD_INVESTIGATOR: 'Fraud investigator',
  UNDERWRITING_ANALYST: 'Underwriting analyst',
  MCP_CLIENT: 'Connected app',
}

/** `get_quote` → "Get quote"; the server sends tool names, never arguments. */
export const toolLabel = (tool: string) => {
  const s = tool.replace(/^ask_/, 'asking ').replace(/_/g, ' ')
  return s.charAt(0).toUpperCase() + s.slice(1)
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** In-app link for a citation, when there's a page for it. */
export function citationHref(c: CitationDto): string | null {
  switch (c.type) {
    case 'ACCOUNT':
      return UUID.test(c.id) ? `/accounts/${c.id}` : '/accounts'
    case 'INSTRUMENT':
      return /^[A-Z0-9]{1,12}$/.test(c.id) ? `/markets/${c.id}` : '/markets'
    case 'LOAN':
      return UUID.test(c.id) ? `/loans/${c.id}` : '/loans'
    case 'RULE':
      return UUID.test(c.id) ? `/autopilot/${c.id}` : '/autopilot'
    case 'METRIC':
      return '/insights'
    default:
      return null
  }
}

export const SUGGESTIONS = [
  'How much did I spend on food this month?',
  'What’s my balance?',
  'Can I afford a ₹3 lakh loan?',
  'Move ₹5,000 to my Goa goal',
]
