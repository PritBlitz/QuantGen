import '@fontsource-variable/geist'
import '@fontsource-variable/geist-mono'
import '@fontsource/instrument-serif/400.css'
import '@fontsource/instrument-serif/400-italic.css'
import './styles/globals.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { registerServiceWorker } from './lib/pwa'

async function enableMocks() {
  // DEV guard first, so production builds drop MSW entirely.
  if (!import.meta.env.DEV || import.meta.env.VITE_USE_MOCKS !== 'true') return
  const { worker } = await import('./mocks/browser')
  await worker.start({ onUnhandledFrame: 'bypass', quiet: true })
}

registerServiceWorker()

void enableMocks().then(() => {
  const root = document.getElementById('root')
  if (!root) throw new Error('#root missing from index.html')
  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})
