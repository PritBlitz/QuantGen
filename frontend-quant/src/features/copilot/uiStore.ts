import { create } from 'zustand'
import type { CopilotContext } from '@/api/types'
import { useAuthStore } from '@/features/auth/store'

/**
 * Open/close state of the Copilot sheet, an optional prompt to send on open, and the thread
 * shared by the sheet and the `/copilot` page. In memory only.
 */
export interface PendingPrompt {
  text: string
  context?: CopilotContext
}

interface CopilotUi {
  isOpen: boolean
  /** A question to send as soon as the sheet opens (from ⌘K, "Explain this run", …). */
  pendingPrompt: PendingPrompt | null
  /** The conversation the sheet and the page show; null = start a new one on first send. */
  conversationId: string | null
  /** `context` lets the server attach the item the question is about (e.g. an Autopilot run). */
  open: (prompt?: string, context?: CopilotContext) => void
  close: () => void
  takePendingPrompt: () => PendingPrompt | null
  setConversation: (id: string | null) => void
}

export const useCopilotUi = create<CopilotUi>()((set, get) => ({
  isOpen: false,
  pendingPrompt: null,
  conversationId: null,
  open: (prompt, context) => {
    const text = prompt?.trim()
    set({ isOpen: true, pendingPrompt: text ? { text, ...(context ? { context } : {}) } : null })
  },
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
