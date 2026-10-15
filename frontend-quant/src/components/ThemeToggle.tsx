import { Moon, Sun } from 'lucide-react'
import { useTheme } from '@/lib/theme'
import { Button } from './ui/button'

export function ThemeToggle({ className }: { className?: string }) {
  const theme = useTheme((s) => s.theme)
  const toggle = useTheme((s) => s.toggle)
  const next = theme === 'dark' ? 'light' : 'dark'
  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={toggle}
      aria-label={`Switch to ${next} theme`}
      className={className}
    >
      {theme === 'dark' ? <Sun /> : <Moon />}
    </Button>
  )
}
