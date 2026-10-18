import type { StepUpChallenge } from './types'

/**
 * Bridge between the API client and the step-up dialog (PRD §12.1, D-029). The dialog registers
 * a handler on mount; the client calls `requestStepUp` on `401 STEP_UP_REQUIRED` and waits for a
 * short-lived step-up token (or `null` if the user cancels). The token is never stored: it is
 * attached to the one retried request and then dropped.
 */
type Handler = (challenge: StepUpChallenge) => Promise<string | null>

let handler: Handler | null = null
let inFlight: { action: StepUpChallenge['action']; promise: Promise<string | null> } | null = null

export function setStepUpHandler(next: Handler | null): void {
  handler = next
}

export function requestStepUp(challenge: StepUpChallenge): Promise<string | null> {
  const h = handler
  if (!h) return Promise.resolve(null)
  // Parallel requests for the same action share one dialog; a different action (tokens are
  // action-bound) waits for the open dialog to finish, then asks again.
  if (inFlight) {
    if (inFlight.action === challenge.action) return inFlight.promise
    return inFlight.promise.then(() => requestStepUp(challenge))
  }
  const promise = h(challenge).finally(() => {
    inFlight = null
  })
  inFlight = { action: challenge.action, promise }
  return promise
}
