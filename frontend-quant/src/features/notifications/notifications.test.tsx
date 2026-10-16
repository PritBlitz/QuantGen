import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/handlers'
import { DEMO_USER_ID } from '@/mocks/db'
import { renderApp, signInAs } from '@/test/utils'

const unreadInDb = () => db.notifications.filter((n) => n.userId === DEMO_USER_ID && !n.read).length

describe('notifications', () => {
  it('lists newest first, filters unread and marks one as read', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/notifications')

    expect(await screen.findByRole('heading', { name: 'Notifications' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Notifications, 3 unread' })).toBeInTheDocument()
    expect(await screen.findByText('Loan approved')).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /unread/i }))
    await waitFor(() => expect(screen.queryByText('Loan approved')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Mark read: EMI paid' }))
    await waitFor(() => expect(unreadInDb()).toBe(2))
    expect(await screen.findByRole('link', { name: 'Notifications, 2 unread' })).toBeInTheDocument()
  })

  it('marks everything read and then shows the caught-up state', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/notifications')
    const markAll = await screen.findByRole('button', { name: 'Mark all as read' })
    await waitFor(() => expect(markAll).toBeEnabled())
    await user.click(markAll)
    expect(await screen.findByText('You’re all caught up.')).toBeInTheDocument()
    expect(unreadInDb()).toBe(0)
    expect(screen.getByRole('link', { name: 'Notifications' })).toBeInTheDocument()
    await waitFor(() => expect(markAll).toBeDisabled())
  })

  it('opening a notification marks it read and goes to the related screen', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    const { router } = renderApp('/notifications')
    const row = (await screen.findByText('Money received')).closest('li')!
    await user.click(within(row).getByRole('button', { name: 'Money received (unread)' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/accounts'))
    await waitFor(() =>
      expect(db.notifications.find((n) => n.title === 'Money received')?.read).toBe(true),
    )
  })
})
