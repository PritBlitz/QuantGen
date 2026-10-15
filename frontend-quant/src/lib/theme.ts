import { create } from 'zustand'
import { readTheme, writeTheme, type Theme } from './prefs'

interface ThemeState {
  theme: Theme
  setTheme: (t: Theme) => void
  toggle: () => void
}

function apply(theme: Theme) {
  document.documentElement.setAttribute('data-theme', theme)
}

export const useTheme = create<ThemeState>()((set, get) => ({
  theme: typeof document === 'undefined' ? 'dark' : readTheme(),
  setTheme: (theme) => {
    apply(theme)
    writeTheme(theme)
    set({ theme })
  },
  toggle: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
}))
