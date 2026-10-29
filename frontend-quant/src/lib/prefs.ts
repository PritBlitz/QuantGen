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
  return 'light'
}

export function writeTheme(theme: Theme): void {
  try {
    localStorage.setItem(THEME_KEY, theme)
  } catch {
    /* storage blocked: the choice lasts for this page view only */
  }
}

const ONBOARDING_KEY = 'qg.onboarding.hidden'

/** Whether the dashboard's "get set up" checklist was dismissed in this browser. */
export function readOnboardingHidden(): boolean {
  try {
    return localStorage.getItem(ONBOARDING_KEY) === '1'
  } catch {
    return false
  }
}

export function writeOnboardingHidden(hidden: boolean): void {
  try {
    if (hidden) localStorage.setItem(ONBOARDING_KEY, '1')
    else localStorage.removeItem(ONBOARDING_KEY)
  } catch {
    /* storage blocked: hidden for this page view only */
  }
}
