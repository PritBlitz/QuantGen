import { Maximize2, SquarePen } from 'lucide-react'
import { useEffect } from 'react'
import { Link, useLocation } from 'react-router'
import { useFlag } from '@/api/flags'
import { Button } from '@/components/ui/button'
import { Dialog, SheetContent } from '@/components/ui/dialog'
import { ChatView } from './components/ChatView'
import { useCopilotChat } from './hooks'
import { useCopilotUi } from './uiStore'

/**
 * Copilot in a side sheet (bottom sheet on phones), opened from the top bar, ⌘K, the dock and
 * the dashboard strip. Shares its thread with the `/copilot` page. Hidden when `ai` is off.
 */
export function CopilotSheet() {
  const on = useFlag('ai')
  const { isOpen, close, takePendingPrompt } = useCopilotUi()
  const { pathname } = useLocation()
  const onPage = pathname.startsWith('/copilot')
  const chat = useCopilotChat()
  const { send, newChat } = chat

  // A prompt handed over by an entry point (strip, ⌘K…) is sent once the sheet is open.
  useEffect(() => {
    if (!isOpen || !on) return
    const p = takePendingPrompt()
    if (p) void send(p.text, p.context)
  }, [isOpen, on, takePendingPrompt, send])

  // On the full page the sheet would duplicate it.
  useEffect(() => {
    if (onPage && isOpen) close()
  }, [onPage, isOpen, close])

  if (!on) return null
  return (
    <Dialog open={isOpen} onOpenChange={(o) => !o && close()}>
      <SheetContent
        title="Copilot"
        description="Answers from your own data. You approve every action."
        className="max-md:inset-x-0 max-md:bottom-0 max-md:top-auto max-md:h-[88dvh] max-md:max-w-none max-md:rounded-b-none md:max-w-[480px]"
      >
        <div className="flex items-center gap-1 px-5 pb-1 pt-2 pr-14">
          <Button variant="ghost" size="sm" onClick={newChat}>
            <SquarePen aria-hidden="true" /> New chat
          </Button>
          <Button variant="ghost" size="sm" asChild>
            <Link to="/copilot" onClick={close}>
              <Maximize2 aria-hidden="true" /> Full page
            </Link>
          </Button>
        </div>
        <ChatView chat={chat} autoFocus />
      </SheetContent>
    </Dialog>
  )
}
