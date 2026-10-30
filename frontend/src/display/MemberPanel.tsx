import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, deleteRequest, postJson } from '../api/http'
import { CountryPicker } from '../components/CountryPicker'
import { DateField } from '../components/DateField'
import { PhoneInput } from '../components/PhoneInput'
import { usePhoneNumber } from '../components/phone'
import { FUTURE_BIRTH_DATE, isFutureDate, todayIso } from '../lib/dates'
import { displayPaths, type DisplayState, type MemberStatus, type SignUpRequest } from './types'

type Mode = 'menu' | 'login' | 'signup'

interface Waiting {
  status: MemberStatus
  firstName: string | null
}

function errorMessage(error: unknown) {
  return error instanceof ApiError ? error.message : 'Offline'
}

/** After the till adds the member, how long until their name is on the order the till publishes. */
const ADDING_GRACE_MS = 4000

/**
 * "Are you a member?": Member Login by phone number or Member Sign Up. The till adds them to the sale by itself; `memberName` is the member on the sale,
 * however they were added (here or on the till).
 */
/** The membership side of the display, on charcoal: its paragraphs small and light grey. */
const panel = 'flex flex-col gap-3 [&_p]:text-zinc-300'
/** Member Login and Sign Up: a white card on the charcoal. */
const card = 'flex flex-col gap-3 rounded-3xl bg-white p-5 text-zinc-900 shadow-2xl shadow-black/40 [&_p]:text-zinc-500'
const big = 'text-[1.7rem] leading-tight font-bold tracking-tight text-white!'
const bigOnCard = 'text-[1.7rem] leading-tight font-bold tracking-tight text-zinc-900!'
const primary = 'rounded-2xl bg-linear-to-r from-indigo-600 to-violet-600 p-4 text-lg font-semibold text-white shadow-lg shadow-indigo-600/25 transition enabled:hover:brightness-110 enabled:active:scale-[0.98] disabled:opacity-50'
const secondary = 'rounded-2xl bg-white p-4 text-lg font-semibold text-zinc-700 shadow-xs ring-1 ring-zinc-200 transition ring-inset enabled:hover:bg-zinc-50 enabled:active:scale-[0.98]'
/** A grey button on the charcoal (Register Member). */
const secondaryOnDark = 'rounded-2xl bg-zinc-600 p-4 text-lg font-semibold text-white shadow-lg shadow-black/20 ring-1 ring-white/10 transition ring-inset enabled:hover:bg-zinc-500 enabled:active:scale-[0.98]'
// The sign-up form.
const row = 'flex gap-3 max-md:flex-col [&>*]:min-w-0'
const textLabel = 'flex flex-1 flex-col gap-1.5 text-sm font-medium text-zinc-700'
const textInput = 'rounded-xl bg-white px-3 py-2.5 text-base font-normal text-zinc-900 shadow-xs ring-1 ring-zinc-200 ring-inset focus:ring-2 focus:ring-indigo-500 focus:outline-none'
const choice = 'mr-4 inline-flex flex-row items-center gap-2 py-1 text-sm font-medium text-zinc-700 [&_input]:size-4 [&_input]:accent-indigo-600'

export function MemberPanel({ state, memberName }: { state: DisplayState | null; memberName: string | null }) {
  const [mode, setMode] = useState<Mode>('menu')
  // Shown at once after logging in / signing up, until the next poll brings the server's status.
  const [sent, setSent] = useState<Waiting | null>(null)
  const server = state?.member ?? { status: 'NONE' as MemberStatus, firstName: null }
  // Just added by the till: their name is on the order the till publishes next.
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    if (server.status !== 'NONE') setSent(null)
    if (server.status !== 'CONFIRMED') return
    setAdding(true)
    const timer = window.setTimeout(() => setAdding(false), ADDING_GRACE_MS)
    return () => window.clearTimeout(timer)
  }, [server.status])

  const member = server.status !== 'NONE' ? server : (sent ?? server)

  async function cancel() {
    setSent(null)
    try {
      await deleteRequest(displayPaths.member)
    } catch {
      // The next poll shows whatever the server has.
    }
  }

  if (memberName) {
    return (
      <div className={`${panel} text-center`} role="status">
        <p className={big}>Welcome, {memberName}!</p>
        <p>Your membership is on this order.</p>
      </div>
    )
  }
  if (member.status === 'WAITING' || (member.status === 'CONFIRMED' && adding)) {
    return (
      <div className={`${panel} text-center`} role="status">
        <p className={big}>Thanks, {member.firstName}!</p>
        <p>Adding your membership to this order…</p>
        {member.status === 'WAITING' && (
          <button type="button" className={secondaryOnDark} onClick={() => void cancel()}>
            Cancel
          </button>
        )}
      </div>
    )
  }

  const done = (waiting: Waiting) => {
    setSent(waiting)
    setMode('menu')
  }
  switch (mode) {
    case 'login':
      return <MemberLogin onDone={done} onBack={() => setMode('menu')} />
    case 'signup':
      return <MemberSignUp onDone={done} onBack={() => setMode('menu')} />
    case 'menu':
      return (
        <div className={`${panel} text-center`}>
          <p className={big}>Are you a member?</p>
          <p>Members get free treats and rewards with every purchase.</p>
          <button type="button" className={primary} onClick={() => setMode('login')}>
            Login
          </button>
          <button type="button" className={secondaryOnDark} onClick={() => setMode('signup')}>
            Register Member
          </button>
        </div>
      )
  }
}

const PAD_KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '0', 'back']

/**
 * The phone number on a keypad, with its country: picked from the list, or detected from a number
 * that starts with "+" (as in the till's phone field).
 */
function MemberLogin({ onDone, onBack }: { onDone: (waiting: Waiting) => void; onBack: () => void }) {
  const [international, setInternational] = useState('')
  const phone = usePhoneNumber('', setInternational)
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const digits = phone.text.replace(/\D/g, '')

  function press(key: string) {
    setError(null)
    if (key === 'clear') phone.type('')
    else if (key === 'back') phone.type(phone.text.slice(0, -1))
    else if (key === '+') {
      if (phone.text === '') phone.type('+')
    } else if (phone.text.length < 20) phone.type(phone.text + key)
  }

  async function submit() {
    setSending(true)
    try {
      onDone(await postJson<Waiting>(displayPaths.memberLogin, { phone: international }))
    } catch (caught) {
      setError(errorMessage(caught))
      setSending(false)
    }
  }

  return (
    <div className={`${card} text-center`}>
      <p className={bigOnCard}>Member Login</p>
      <p>Enter your phone number</p>
      <div className="flex items-stretch rounded-2xl bg-zinc-50 ring-1 ring-zinc-200 ring-inset">
        <CountryPicker country={phone.country} callingCode={phone.callingCode} disabled={sending} onChange={phone.setCountry} keypad />
        <output className="block min-w-0 flex-1 overflow-hidden px-3 py-2.5 text-left text-[1.7rem] font-semibold tracking-wider text-zinc-900 tabular-nums whitespace-nowrap" aria-label="Phone number">{phone.text || ' '}</output>
      </div>
      <div className="grid grid-cols-3 gap-2 [&_button]:rounded-2xl [&_button]:bg-white [&_button]:px-0 [&_button]:py-3.5 [&_button]:text-2xl [&_button]:font-semibold [&_button]:text-zinc-800 [&_button]:shadow-xs [&_button]:ring-1 [&_button]:ring-zinc-200 [&_button]:transition [&_button]:ring-inset [&_button:enabled:active]:scale-95 [&_button:enabled:active]:bg-indigo-50 [&_button:enabled:hover]:bg-zinc-50">
        {PAD_KEYS.map((key) => (
          <button
            key={key}
            type="button"
            aria-label={key === 'back' ? 'Delete digit' : key}
            disabled={sending || (key === '+' && phone.text !== '')}
            onClick={() => press(key)}
          >
            {key === 'back' ? '⌫' : key}
          </button>
        ))}
      </div>
      {error && <p className="rounded-xl bg-red-50 px-3 py-2 text-sm font-medium text-red-700! ring-1 ring-red-200 ring-inset" role="alert">{error}</p>}
      <button type="button" className={`${secondary} p-2.5! text-sm!`} disabled={sending} onClick={() => press('clear')}>
        Clear
      </button>
      <button type="button" className={primary} disabled={digits.length < 7 || sending} onClick={() => void submit()}>
        Login
      </button>
      <button type="button" className={secondary} onClick={onBack}>
        Back
      </button>
    </div>
  )
}

function MemberSignUp({ onDone, onBack }: { onDone: (waiting: Waiting) => void; onBack: () => void }) {
  const [form, setForm] = useState({
    phone: '', firstName: '', lastName: '', email: '', gender: '' as '' | 'MALE' | 'FEMALE', dateOfBirth: '',
    marketingEmail: false, marketingSms: false, marketingCall: false,
  })
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const set = <K extends keyof typeof form>(key: K, value: (typeof form)[K]) => setForm({ ...form, [key]: value })
  // A birth date after today: said at the field, and never sent.
  const birthMessage = form.dateOfBirth && isFutureDate(form.dateOfBirth) ? FUTURE_BIRTH_DATE : null

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (birthMessage) return
    setSending(true)
    setError(null)
    const request: SignUpRequest = {
      phone: form.phone.trim(),
      firstName: form.firstName.trim(),
      lastName: form.lastName.trim(),
      email: form.email.trim() || null,
      gender: form.gender || null,
      dateOfBirth: form.dateOfBirth || null,
      marketingEmail: form.marketingEmail,
      marketingSms: form.marketingSms,
      marketingCall: form.marketingCall,
    }
    try {
      onDone(await postJson<Waiting>(displayPaths.members, request))
    } catch (caught) {
      setError(errorMessage(caught))
      setSending(false)
    }
  }

  return (
    <form className={`${card} text-left`} onSubmit={(event) => void submit(event)} aria-label="Member Sign Up">
      <p className={bigOnCard}>Member Sign Up</p>
      <PhoneInput value={form.phone} required onDisplay onChange={(phone) => setForm((current) => ({ ...current, phone }))} />
      <div className={row}>
        <label className={textLabel}>
          First name *
          <input className={`${textInput} w-full min-w-0`} required maxLength={60} value={form.firstName} onChange={(event) => set('firstName', event.target.value)} />
        </label>
        <label className={textLabel}>
          Last name *
          <input className={`${textInput} w-full min-w-0`} required maxLength={60} value={form.lastName} onChange={(event) => set('lastName', event.target.value)} />
        </label>
      </div>
      <label className={textLabel}>
        Email
        <input className={textInput} type="email" maxLength={254} value={form.email} onChange={(event) => set('email', event.target.value)} />
      </label>
      <div className={row}>
        <fieldset className="flex-1 rounded-2xl bg-zinc-50 px-3.5 pt-2 pb-2.5 text-sm ring-1 ring-zinc-200/70 ring-inset">
          <legend className="float-left mb-1 w-full text-xs font-semibold tracking-wider text-zinc-500 uppercase">Gender</legend>
          <label className={choice}>
            <input type="radio" name="gender" checked={form.gender === 'MALE'} onChange={() => set('gender', 'MALE')} /> Male
          </label>
          <label className={choice}>
            <input type="radio" name="gender" checked={form.gender === 'FEMALE'} onChange={() => set('gender', 'FEMALE')} /> Female
          </label>
        </fieldset>
        <div className="flex flex-1 flex-col gap-1.5 text-sm font-medium text-zinc-700">
          Date of birth
          <DateField value={form.dateOfBirth} max={todayIso()} onChange={(dateOfBirth) => setForm((current) => ({ ...current, dateOfBirth }))} />
          {birthMessage && <span className="text-sm font-medium text-red-700" role="alert">{birthMessage}</span>}
        </div>
      </div>
      <fieldset className="flex-1 rounded-2xl bg-zinc-50 px-3.5 pt-2 pb-2.5 text-sm ring-1 ring-zinc-200/70 ring-inset">
        <legend className="float-left mb-1 w-full text-xs font-semibold tracking-wider text-zinc-500 uppercase">Send me offers by</legend>
        <label className={choice}>
          <input type="checkbox" checked={form.marketingEmail} onChange={(event) => set('marketingEmail', event.target.checked)} /> Email
        </label>
        <label className={choice}>
          <input type="checkbox" checked={form.marketingSms} onChange={(event) => set('marketingSms', event.target.checked)} /> SMS
        </label>
        <label className={choice}>
          <input type="checkbox" checked={form.marketingCall} onChange={(event) => set('marketingCall', event.target.checked)} /> Phone call
        </label>
      </fieldset>
      {error && <p className="rounded-xl bg-red-50 px-3 py-2 text-sm font-medium text-red-700! ring-1 ring-red-200 ring-inset" role="alert">{error}</p>}
      <button type="submit" className={primary} disabled={sending || birthMessage !== null}>
        {sending ? 'Registering…' : 'Register'}
      </button>
      <button type="button" className={secondary} onClick={onBack}>
        Back
      </button>
    </form>
  )
}
