import { Command } from 'cmdk'
import { LogOut, Moon, Plus, Search, Sun } from 'lucide-react'
import { useEffect, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { useTheme } from '@/lib/theme'
import { NAV, SECONDARY_NAV } from './nav'

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
  const theme = useTheme((s) => s.theme)
  const toggleTheme = useTheme((s) => s.toggle)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        onOpenChange(!open)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onOpenChange])

  const run = (fn: () => void) => () => {
    onOpenChange(false)
    fn()
  }

  return (
    <Command.Dialog
      open={open}
      onOpenChange={onOpenChange}
      label="Command menu"
      overlayClassName="fixed inset-0 z-50 bg-black/60 backdrop-blur-[2px]"
      contentClassName="fixed left-1/2 top-[18vh] z-50 w-[calc(100vw-2rem)] max-w-lg -translate-x-1/2 overflow-hidden rounded-[var(--radius-card)] bg-surface hairline shadow-[var(--shadow-pop)]"
    >
      <div className="flex items-center gap-2 border-b border-border px-4">
        <Search aria-hidden="true" className="size-4 text-muted" />
        <Command.Input
          placeholder="Jump to… or type a command"
          className="h-12 w-full bg-transparent text-sm outline-none placeholder:text-muted"
        />
      </div>
      <Command.List className="max-h-80 overflow-y-auto p-2">
        <Command.Empty className="px-3 py-6 text-center text-sm text-muted">
          No results.
        </Command.Empty>
        <Group heading="Actions">
          <Item onSelect={run(() => void navigate('/transfer'))} icon={<Plus />}>
            Transfer money
          </Item>
          <Item onSelect={run(() => void navigate('/markets/QGEN'))} icon={<Plus />}>
            Buy QGEN
          </Item>
          <Item onSelect={run(toggleTheme)} icon={theme === 'dark' ? <Sun /> : <Moon />}>
            Switch to {theme === 'dark' ? 'light' : 'dark'} theme
          </Item>
        </Group>
        <Group heading="Go to">
          {[...NAV, ...SECONDARY_NAV].map((item) => (
            <Item key={item.to} onSelect={run(() => void navigate(item.to))} icon={<item.icon />}>
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
}: {
  children: ReactNode
  icon: ReactNode
  onSelect: () => void
}) {
  return (
    <Command.Item
      onSelect={onSelect}
      className="flex cursor-pointer items-center gap-3 rounded-[var(--radius-control)] px-3 py-2.5 text-sm text-text data-[selected=true]:bg-surface-2 [&_svg]:size-4 [&_svg]:text-muted"
    >
      {icon}
      {children}
    </Command.Item>
  )
}
