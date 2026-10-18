import { Command } from 'cmdk'
import { LogOut, Moon, Plus, Search, Sparkles, Sun } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { useFlag } from '@/api/flags'
import { useCopilotUi } from '@/features/copilot/uiStore'
import { useTheme } from '@/lib/theme'
import { SECONDARY_NAV, useMainNav } from './nav'
import { useAuthStore } from '@/features/auth/store'

/** ⌘K / Ctrl+K palette (DESIGN.md §3.4). */
export function CommandPalette({
  open,
  onOpenChange,
  onLogout,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onLogout: () => void
}) {
  const navigate = useNavigate()
  const nav = useMainNav()
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const theme = useTheme((s) => s.theme)
  const toggleTheme = useTheme((s) => s.toggle)
  const aiOn = useFlag('ai') && !isAdmin
  const openCopilot = useCopilotUi((s) => s.open)
  const [search, setSearch] = useState('')
  // Every close clears the query, so each opening starts empty.
  const setOpen = (next: boolean) => {
    if (!next) setSearch('')
    onOpenChange(next)
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        setOpen(!open)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const run = (fn: () => void) => () => {
    setOpen(false)
    fn()
  }

  return (
    <Command.Dialog
      open={open}
      onOpenChange={setOpen}
      label="Command menu"
      overlayClassName="fixed inset-0 z-50 bg-black/60 backdrop-blur-[2px]"
      contentClassName="fixed left-1/2 top-[18vh] z-50 w-[calc(100vw-2rem)] max-w-lg -translate-x-1/2 overflow-hidden rounded-[var(--radius-card)] bg-surface hairline shadow-[var(--shadow-pop)]"
    >
      <div className="flex items-center gap-2 border-b border-border px-4">
        <Search aria-hidden="true" className="size-4 text-muted" />
        <Command.Input
          value={search}
          onValueChange={setSearch}
          placeholder={aiOn ? 'Jump to… or ask Copilot anything' : 'Jump to… or type a command'}
          className="h-12 w-full bg-transparent text-sm outline-none placeholder:text-muted"
        />
      </div>
      <Command.List className="max-h-80 overflow-y-auto p-2">
        <Command.Empty className="px-3 py-6 text-center text-sm text-muted">
          No results.
        </Command.Empty>
        {aiOn && (
          <Group heading="Copilot">
            {/* Always matches whatever was typed (forceMount), so a question never shows "No results". */}
            <Command.Item
              value={`ask-copilot ${search}`}
              forceMount
              onSelect={run(() => openCopilot(search))}
              className="flex cursor-pointer items-center gap-3 rounded-[var(--radius-control)] px-3 py-2.5 text-sm text-text data-[selected=true]:bg-violet/12 [&_svg]:size-4 [&_svg]:text-violet"
            >
              <Sparkles aria-hidden="true" />
              <span className="truncate">{search.trim() ? <>Ask Copilot: “{search.trim()}”</> : 'Ask Copilot'}</span>
            </Command.Item>
          </Group>
        )}
        <Group heading="Actions">
          {isAdmin ? (
            <Item onSelect={run(() => void navigate('/admin/loans'))} icon={<Plus />}>
              Review pending loans
            </Item>
          ) : (
            <>
              <Item onSelect={run(() => void navigate('/transfer'))} icon={<Plus />}>
                Transfer money
              </Item>
              <Item onSelect={run(() => void navigate('/markets/QGEN'))} icon={<Plus />}>
                Buy QGEN
              </Item>
            </>
          )}
          <Item onSelect={run(toggleTheme)} icon={theme === 'dark' ? <Sun /> : <Moon />}>
            Switch to {theme === 'dark' ? 'light' : 'dark'} theme
          </Item>
        </Group>
        <Group heading="Go to">
          {[...nav, ...SECONDARY_NAV].map((item) => (
            <Item key={item.to} onSelect={run(() => void navigate(item.to))} icon={<item.icon />} hint={item.hint}>
              {item.label}
            </Item>
          ))}
        </Group>
        <Group heading="Session">
          <Item onSelect={run(onLogout)} icon={<LogOut />}>
            Sign out
          </Item>
        </Group>
      </Command.List>
    </Command.Dialog>
  )
}

function Group({ heading, children }: { heading: string; children: ReactNode }) {
  return (
    <Command.Group
      heading={heading}
      className="[&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:pt-3 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-muted"
    >
      {children}
    </Command.Group>
  )
}

function Item({
  children,
  icon,
  onSelect,
  hint,
}: {
  children: ReactNode
  icon: ReactNode
  onSelect: () => void
  hint?: string
}) {
  return (
    <Command.Item
      onSelect={onSelect}
      className="flex cursor-pointer items-center gap-3 rounded-[var(--radius-control)] px-3 py-2.5 text-sm text-text data-[selected=true]:bg-surface-2 [&_svg]:size-4 [&_svg]:text-muted"
    >
      {icon}
      {children}
      {hint && <span className="ml-auto truncate pl-3 text-xs text-muted">{hint}</span>}
    </Command.Item>
  )
}
