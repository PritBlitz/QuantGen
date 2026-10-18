import { setupServer } from 'msw/node'
import { handlers } from './allHandlers'

export const server = setupServer(...handlers)
