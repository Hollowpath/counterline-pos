import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmProvider } from './ConfirmProvider'
import { useConfirm, type ConfirmOptions } from './confirmContext'
import { Dialog } from './Dialog'

/** A page with one button that asks, and shows the answer. */
function Page({ options = { title: 'Delete the Gifts tab?', message: 'The tab is empty.', confirmLabel: 'Delete tab' }, onAnswer = () => {} }: {
  options?: ConfirmOptions
  onAnswer?: (answer: boolean) => void
}) {
  const confirm = useConfirm()
  const [answer, setAnswer] = useState('none')
  return (
    <>
      <button type="button" onClick={async () => {
        const result = await confirm(options)
        setAnswer(result ? 'yes' : 'no')
        onAnswer(result)
      }}>
        Ask
      </button>
      <p data-testid="answer">{answer}</p>
    </>
  )
}

const renderPage = (props: Parameters<typeof Page>[0] = {}) =>
  render(<ConfirmProvider><Page {...props} /></ConfirmProvider>)

describe('confirmation dialog', () => {
  it('asks in an in-app alert dialog and never uses the browser’s prompt', async () => {
    const native = vi.spyOn(window, 'confirm')
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Ask' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete the Gifts tab?' })
    expect(dialog).toHaveAccessibleDescription('The tab is empty.')
    expect(within(dialog).getByRole('button', { name: 'Delete tab' })).toHaveClass('btn-danger')
    expect(native).not.toHaveBeenCalled()
    native.mockRestore()
  })

  it('resolves true only for the named action, and focuses Cancel first so Enter cannot confirm', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Ask' }))
    expect(await screen.findByRole('button', { name: 'Cancel' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('answer')).toHaveTextContent('no')

    await user.click(screen.getByRole('button', { name: 'Ask' }))
    await user.click(await screen.findByRole('button', { name: 'Delete tab' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('answer')).toHaveTextContent('yes')
  })

  it('Escape and a click on the backdrop both cancel, and focus returns to the button that asked', async () => {
    const user = userEvent.setup()
    renderPage()
    const ask = screen.getByRole('button', { name: 'Ask' })

    await user.click(ask)
    await screen.findByRole('alertdialog')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('answer')).toHaveTextContent('no')
    expect(ask).toHaveFocus()

    await user.click(ask)
    const backdrop = (await screen.findByRole('alertdialog')).parentElement!
    await user.pointer({ keys: '[MouseLeft]', target: backdrop })
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('answer')).toHaveTextContent('no')
  })

  it('keeps Tab inside the dialog', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: 'Ask' }))
    const dialog = await screen.findByRole('alertdialog')

    await user.tab()
    expect(within(dialog).getByRole('button', { name: 'Delete tab' })).toHaveFocus()
    await user.tab()
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus()
    await user.tab({ shift: true })
    expect(within(dialog).getByRole('button', { name: 'Delete tab' })).toHaveFocus()
  })

  it('a second question replaces the first, which is answered no', async () => {
    const answers: boolean[] = []
    function Two() {
      const confirm = useConfirm()
      return (
        <>
          <button type="button" onClick={() => void confirm({ title: 'First?', confirmLabel: 'Yes first' }).then((a) => answers.push(a))}>One</button>
          <button type="button" onClick={() => void confirm({ title: 'Second?', confirmLabel: 'Yes second' }).then((a) => answers.push(a))}>Two</button>
        </>
      )
    }
    render(<ConfirmProvider><Two /></ConfirmProvider>)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'One' }))
    await screen.findByRole('alertdialog', { name: 'First?' })
    // The open dialog makes the rest of the page inert for the pointer, so ask again programmatically.
    screen.getByRole('button', { name: 'Two', hidden: true }).click()
    expect(await screen.findByRole('alertdialog', { name: 'Second?' })).toBeInTheDocument()
    expect(answers).toEqual([false])
  })

  it('answers once however often the button is pressed, and a question open when the provider unmounts is answered no', async () => {
    const answers: boolean[] = []
    function Ask() {
      const confirm = useConfirm()
      return <button type="button" onClick={() => void confirm({ title: 'Sure?', confirmLabel: 'Do it' }).then((a) => answers.push(a))}>Ask</button>
    }
    const user = userEvent.setup()
    const first = render(<ConfirmProvider><Ask /></ConfirmProvider>)
    await user.click(screen.getByRole('button', { name: 'Ask' }))
    await user.dblClick(await screen.findByRole('button', { name: 'Do it' }))
    expect(answers).toEqual([true])
    first.unmount()

    render(<ConfirmProvider><Ask /></ConfirmProvider>)
    await user.click(screen.getByRole('button', { name: 'Ask' }))
    await screen.findByRole('alertdialog')
    const pending = answers.length
    cleanup()
    await vi.waitFor(() => expect(answers.slice(pending)).toEqual([false]))
  })

  it('a primary confirmation is not styled as destructive', async () => {
    const user = userEvent.setup()
    renderPage({ options: { title: 'Publish?', confirmLabel: 'Publish', tone: 'primary' } })
    await user.click(screen.getByRole('button', { name: 'Ask' }))
    expect(await screen.findByRole('button', { name: 'Publish' })).toHaveClass('btn-primary')
  })
})

describe('Dialog', () => {
  it('returns focus safely when the button that opened it has gone', async () => {
    const user = userEvent.setup()
    function Host() {
      const [open, setOpen] = useState(false)
      const [trigger, setTrigger] = useState(true)
      return (
        <>
          {trigger && <button type="button" onClick={() => { setOpen(true); setTrigger(false) }}>Open</button>}
          {open && <Dialog title="Gone" onClose={() => setOpen(false)}><button type="button">Inside</button></Dialog>}
        </>
      )
    }
    render(<Host />)
    await user.click(screen.getByRole('button', { name: 'Open' }))
    expect(await screen.findByRole('dialog', { name: 'Gone' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('moves focus inside, labels itself by its title, and only closes on Escape when told how', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(<Dialog title="Payment"><button type="button">Inside</button></Dialog>)
    const dialog = screen.getByRole('dialog', { name: 'Payment' })
    expect(dialog).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'Payment' })).toBeInTheDocument()

    rerender(<Dialog title="Payment" onClose={onClose}><button type="button">Inside</button></Dialog>)
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
    // A stray click on the backdrop does not lose a half-filled form.
    await user.pointer({ keys: '[MouseLeft]', target: dialog.parentElement! })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
