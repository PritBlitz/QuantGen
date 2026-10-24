import { describe, expect, it } from 'vitest'
import { buildPayLink, parsePayLink } from './payLink'

const O = 'https://qg.example'

describe('payment links (F-PAY-7)', () => {
  it('round-trips handle, amount and note', () => {
    const link = buildPayLink(O, { handle: 'aarav@qg', amount: '500.00', note: 'Dinner' })
    expect(parsePayLink(link, O)).toEqual({ handle: 'aarav@qg', amount: '500.00', note: 'Dinner' })
  })
  it('accepts the legacy quantgen:// form', () => {
    expect(parsePayLink('quantgen://pay?h=meera@qg&am=10', O)).toEqual({
      handle: 'meera@qg',
      amount: '10',
    })
  })
  it('rejects other sites, bad handles and drops bad amounts', () => {
    expect(parsePayLink('https://evil.example/transfer?to=aarav@qg', O)).toBeNull()
    expect(parsePayLink(`${O}/transfer?to=<script>`, O)).toBeNull()
    expect(parsePayLink('not a url', O)).toBeNull()
    expect(parsePayLink(`${O}/transfer?to=aarav@qg&am=1e9`, O)).toEqual({ handle: 'aarav@qg' })
  })
})
