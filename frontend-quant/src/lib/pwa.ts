/**
 * Installable app + offline shell (PRD F-UX-1). Production builds only: in dev, MSW owns the
 * service-worker slot, and Vite serves unhashed modules that shouldn't be cached anyway.
 * public/sw.js caches static assets and the offline page, never API responses.
 */
export function registerServiceWorker(): void {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => {
      /* not installable here (e.g. http on a LAN IP); the app works the same without it */
    })
  })
}
