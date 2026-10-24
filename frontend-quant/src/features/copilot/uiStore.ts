import { create } from 'zustand'
import { useAuthStore } from '@/features/auth/store'

/**
 * Open/close state of the Copilot sheet, an optional prompt to send on open, and the thread
 * shared by the sheet and the `/copilot` page. In memory only.
 */
interface CopilotUi {
  isOpen: boolean
  /** A question to send as soon as the sheet opens (from ⌘K, "Explain this run", …). */
  pendingPrompt: string | null
  /** The conversation the sheet and the page show; null = start a new one on first send. */
  conversationId: string | null
  open: (prompt?: string) => void
  close: () => void
  takePendingPrompt: () => string | null
  setConversation: (id: string | null) => void
}

export const useCopilotUi = create<CopilotUi>()((set, get) => ({
  isOpen: false,
  pendingPrompt: null,
  conversationId: null,
  open: (prompt) => set({ isOpen: true, pendingPrompt: prompt?.trim() || null }),
  close: () => set({ isOpen: false }),
  takePendingPrompt: () => {
    const p = get().pendingPrompt
    if (p) set({ pendingPrompt: null })
    return p
  },
  setConversation: (conversationId) => set({ conversationId }),
}))

// A thread belongs to whoever opened it: forget it on sign-out or when another user signs in.
useAuthStore.subscribe((s, prev) => {
  if (s.user?.id !== prev.user?.id)
    useCopilotUi.setState({ isOpen: false, pendingPrompt: null, conversationId: null })
})
