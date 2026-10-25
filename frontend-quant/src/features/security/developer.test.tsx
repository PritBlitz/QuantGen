import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp, signInAs } from '@/test/utils'

describe('Settings → Developer: connect an MCP client (F-MCP-1)', () => {
  it('shows a Claude Desktop config with the token as a placeholder, passed through env', async () => {
    signInAs('CUSTOMER')
    renderApp('/settings/developer')
    expect(
      await screen.findByRole('heading', { name: 'Connect Claude Desktop' }),
    ).toBeInTheDocument()
    const pre = document.querySelector('pre')!
    const config = JSON.parse(pre.textContent ?? '') as {
      mcpServers: { quantgen: { command: string; args: string[]; env: Record<string, string> } }
    }
    const s = config.mcpServers.quantgen
    expect(s.command).toBe('npx')
    expect(s.args).toEqual([
      'mcp-remote',
      'http://127.0.0.1:8090/mcp',
      '--header',
      'Authorization: Bearer ${QG_PAT}',
    ])
    // Never a real token on the page: only the placeholder.
    expect(s.env.QG_PAT).toBe('qg_pat_YOUR_TOKEN')
    expect(screen.getByRole('button', { name: 'Copy config' })).toBeInTheDocument()
  })
})
