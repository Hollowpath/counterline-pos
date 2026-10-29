import { screen, within } from '@testing-library/react'
import type { UserEvent } from '@testing-library/user-event'

/** Answers the app's own confirmation dialog (it has replaced the browser's window.confirm). */
export async function answerConfirm(user: UserEvent, title: string, button: 'Cancel' | string) {
  const dialog = await screen.findByRole('alertdialog', { name: title })
  await user.click(within(dialog).getByRole('button', { name: button }))
}
