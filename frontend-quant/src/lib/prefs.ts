/**
 * The only module allowed to touch localStorage (enforced by ESLint). It stores UI
 * preferences only. Never tokens, user data or account numbers.
 */
export type Theme = 'dark' | 'light'

const THEME_KEY = 'qg.theme'

export function readTheme(): Theme {
  try {
    const v = localStorage.getItem(THEME_KEY)
    if (v === 'light' || v === 'dark') return v
  } catch {
    /* storage blocked */
  }
  return 'dark'
}

export function writeTheme(theme: Theme): void {
  try {
    localStorage.setItem(THEME_KEY, theme)
  } catch {
    /* storage blocked: the choice lasts for this page view only */
  }
}
