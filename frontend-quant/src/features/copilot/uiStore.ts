import { create } from 'zustand'

/** Open/close state of the Copilot sheet, plus an optional prompt to send on open. In memory only. */
interface CopilotUi {
  isOpen: boolean
  /** A question to send as soon as the sheet opens (from ⌘K, "Explain this run", …). */
  pendingPrompt: string | null
  open: (prompt?: string) => void
  close: () => void
  takePendingPrompt: () => string | null
}

export const useCopilotUi = create<CopilotUi>()((set, get) => ({
  isOpen: false,
  pendingPrompt: null,
  open: (prompt) => set({ isOpen: true, pendingPrompt: prompt?.trim() || null }),
  close: () => set({ isOpen: false }),
  takePendingPrompt: () => {
    const p = get().pendingPrompt
    if (p) set({ pendingPrompt: null })
    return p
  },
}))
