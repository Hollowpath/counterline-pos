import { useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../../api/http'
import { DateField } from '../../components/DateField'
import { Dialog } from '../../components/Dialog'
import { PhoneInput } from '../../components/PhoneInput'
import { FUTURE_BIRTH_DATE, isFutureDate, todayIso } from '../../lib/dates'
import { maskEmail, maskPhone } from '../../lib/privacy'
import { createCustomer, deactivateCustomer, updateCustomer } from './customersApi'
import type { Customer, CustomerFields, Gender } from './types'
import { dialogActions, input as textInput } from '../../styles/ui'

type TextField = Exclude<keyof CustomerFields, 'gender' | 'marketingEmail' | 'marketingSms' | 'marketingCall'>

/** The form: sections (fieldsets) with an uppercase grey title. */
const form = [
  'grid gap-4 [&_fieldset]:rounded-2xl [&_fieldset]:bg-zinc-50 [&_fieldset]:p-4 [&_fieldset]:ring-1 [&_fieldset]:ring-zinc-200/70 [&_fieldset]:ring-inset',
  '[&_legend]:float-left [&_legend]:mb-3 [&_legend]:w-full [&_legend]:text-xs [&_legend]:font-semibold [&_legend]:tracking-wider [&_legend]:text-zinc-500 [&_legend]:uppercase',
].join(' ')
const grid = 'clear-both grid grid-cols-[repeat(auto-fit,minmax(13rem,1fr))] items-start gap-x-3 gap-y-3'
/** A labelled field: the label small and grey above its control. */
const field = 'grid gap-1.5 text-sm font-medium text-zinc-700'
const control = `${textInput} font-normal`

const EMPTY: CustomerFields = {
  referenceId: null, firstName: '', lastName: null, companyName: null, dateOfBirth: null, gender: null,
  email: null, phone: null, marketingEmail: false, marketingSms: false, marketingCall: false,
  streetAddress: null, aptSuite: null, city: null, postalCode: null, country: null, notes: null,
}

interface CustomerFormProps {
  /** Edit this customer; omit to add a new one. */
  customer?: Customer
  onSaved: (customer: Customer) => void
  /** Present for a manager editing a customer: offers to deactivate them. */
  onDeactivated?: () => void
  onCancel: () => void
}

/** Add / Edit Customer, in sections. First name, last name and phone are required. */
export function CustomerForm({ customer, onSaved, onDeactivated, onCancel }: CustomerFormProps) {
  const [fields, setFields] = useState<CustomerFields>(() => (customer ? pickFields(customer) : EMPTY))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmingDeactivate, setConfirmingDeactivate] = useState(false)
  // An existing customer's phone and email stay hidden, as everywhere on the till; "Change" types a new one.
  const [changing, setChanging] = useState({ phone: !customer?.phone, email: !customer?.email })

  /** The hidden current value, or the field for a new one (with a way back to the current one). */
  function contact(name: 'phone' | 'email', label: string, input: ReactNode) {
    const current = customer?.[name]
    if (!changing[name] && current) {
      return (
        <div className={field}>
          {label}
          {name === 'phone' && ' *'}
          <div className="flex min-h-11 items-center justify-between gap-2 rounded-xl bg-white py-1 pr-1 pl-3.5 text-base font-normal text-zinc-900 tabular-nums shadow-xs ring-1 ring-zinc-200 ring-inset">
            <span>{name === 'phone' ? maskPhone(current) : maskEmail(current)}</span>
            <button
              type="button"
              className="btn"
              aria-label={`Change ${label.toLowerCase()}`}
              disabled={busy}
              onClick={() => {
                setChanging((now) => ({ ...now, [name]: true }))
                setFields((now) => ({ ...now, [name]: null }))
              }}
            >
              Change
            </button>
          </div>
        </div>
      )
    }
    return (
      <div className="grid gap-[0.2rem]">
        {input}
        {current && (
          <button
            type="button"
            className="justify-self-start text-xs font-semibold text-indigo-600 hover:text-indigo-500"
            disabled={busy}
            onClick={() => {
              setChanging((now) => ({ ...now, [name]: false }))
              setFields((now) => ({ ...now, [name]: current }))
            }}
          >
            Keep the current {label.toLowerCase()}
          </button>
        )}
      </div>
    )
  }

  function text(name: TextField, label: string, options: { type?: string; required?: boolean; maxLength: number }) {
    return (
      <label className={field}>
        {label}
        {options.required && ' *'}
        <input
          className={control}
          type={options.type ?? 'text'}
          value={fields[name] ?? ''}
          required={options.required}
          maxLength={options.maxLength}
          disabled={busy}
          onChange={(event) => setFields({ ...fields, [name]: event.target.value })}
        />
      </label>
    )
  }

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Offline')
      setBusy(false)
    }
  }

  // A birth date after today: said at the field, and never sent.
  const birthMessage = fields.dateOfBirth && isFutureDate(fields.dateOfBirth) ? FUTURE_BIRTH_DATE : null

  function save(event: FormEvent) {
    event.preventDefault()
    if (birthMessage) return
    const payload = normalise(fields)
    void run(async () => onSaved(customer ? await updateCustomer(customer.id, customer.version, payload) : await createCustomer(payload)))
  }

  return (
    <Dialog title={customer ? 'Edit Customer' : 'Add Customer'} wide onClose={busy ? undefined : onCancel}>
      <form className={form} onSubmit={save}>
        {customer && onDeactivated && (
          <div className="flex flex-wrap items-center gap-2">
            {confirmingDeactivate ? (
              <>
                <span className="flex-1 text-sm font-medium text-red-600">
                  Deactivate {customer.fullName}? They keep their points and order history, can't be used on a sale, and can be reactivated.
                </span>
                <button type="button" className="btn" disabled={busy} onClick={() => setConfirmingDeactivate(false)}>
                  Keep
                </button>
                <button
                  type="button"
                  className="btn btn-danger"
                  disabled={busy}
                  onClick={() => void run(async () => {
                    await deactivateCustomer(customer.id)
                    onDeactivated()
                  })}
                >
                  Confirm deactivate
                </button>
              </>
            ) : (
              <button type="button" className="btn btn-danger w-full" disabled={busy} onClick={() => setConfirmingDeactivate(true)}>
                Deactivate customer
              </button>
            )}
          </div>
        )}

        <fieldset>
          <legend>Customer Information</legend>
          <div className={grid}>
            {text('firstName', 'First name', { required: true, maxLength: 60 })}
            {text('lastName', 'Last name', { required: true, maxLength: 60 })}
            {text('referenceId', 'Reference ID', { maxLength: 40 })}
            {text('companyName', 'Company name', { maxLength: 120 })}
            <div className={field}>
              Date of birth
              <DateField
                value={fields.dateOfBirth ?? ''}
                disabled={busy}
                max={todayIso()}
                onChange={(dateOfBirth) => setFields((current) => ({ ...current, dateOfBirth: dateOfBirth || null }))}
              />
              {birthMessage && <span className="text-sm font-medium text-red-600" role="alert">{birthMessage}</span>}
            </div>
            <label className={field}>
              Gender
              <select
                className={control}
                value={fields.gender ?? ''}
                disabled={busy}
                onChange={(event) => setFields({ ...fields, gender: (event.target.value || null) as Gender | null })}
              >
                <option value="">Not specified</option>
                <option value="MALE">Male</option>
                <option value="FEMALE">Female</option>
              </select>
            </label>
          </div>
        </fieldset>

        <fieldset>
          <legend>Contact</legend>
          <div className={grid}>
            {contact('email', 'Email', text('email', 'Email', { type: 'email', maxLength: 254 }))}
            {contact(
              'phone',
              'Phone number',
              <PhoneInput
                value={fields.phone ?? ''}
                required
                disabled={busy}
                onChange={(phone) => setFields((current) => ({ ...current, phone }))}
              />,
            )}
          </div>
        </fieldset>

        <fieldset>
          <legend>Marketing Consent</legend>
          <div className="clear-both flex flex-wrap gap-2">
            {(
              [
                ['marketingEmail', 'Email'],
                ['marketingSms', 'Text (SMS)'],
                ['marketingCall', 'Phone (Call)'],
              ] as const
            ).map(([name, label]) => (
              <label key={name} className="flex cursor-pointer items-center gap-2 rounded-full bg-white px-3.5 py-2 text-sm font-medium text-zinc-700 shadow-xs ring-1 ring-zinc-200 transition ring-inset has-checked:bg-indigo-50 has-checked:text-indigo-700 has-checked:ring-indigo-300">
                <input
                  className="size-4 accent-indigo-600"
                  type="checkbox"
                  checked={fields[name]}
                  disabled={busy}
                  onChange={(event) => setFields({ ...fields, [name]: event.target.checked })}
                />
                {label}
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend>Address</legend>
          <div className={grid}>
            {text('streetAddress', 'Street address', { maxLength: 200 })}
            {text('aptSuite', 'Apt / Suite', { maxLength: 60 })}
            {text('city', 'City', { maxLength: 80 })}
            {text('postalCode', 'Postal code', { maxLength: 20 })}
            {text('country', 'Country', { maxLength: 60 })}
          </div>
        </fieldset>

        <fieldset>
          <legend>Notes</legend>
          <textarea
            className={`${control} min-h-18 w-full`}
            aria-label="Notes"
            value={fields.notes ?? ''}
            maxLength={1000}
            disabled={busy}
            onChange={(event) => setFields({ ...fields, notes: event.target.value })}
          />
        </fieldset>

        {error && (
          <p className="rounded-xl bg-red-50 px-3 py-2 text-sm font-medium text-red-700 ring-1 ring-red-200 ring-inset" role="alert">
            {error}
          </p>
        )}
        <div className={dialogActions}>
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || !complete(fields) || birthMessage !== null}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}

/** What every member needs: first name, last name and phone number. */
function complete(fields: CustomerFields): boolean {
  return fields.firstName.trim() !== '' && (fields.lastName ?? '').trim() !== '' && (fields.phone ?? '').trim() !== ''
}

function pickFields(customer: Customer): CustomerFields {
  const picked = { ...EMPTY }
  for (const key of Object.keys(EMPTY) as (keyof CustomerFields)[]) {
    ;(picked as Record<string, unknown>)[key] = customer[key]
  }
  return picked
}

/** Blank text fields are sent as null so the server stores "not provided" rather than "". */
function normalise(fields: CustomerFields): CustomerFields {
  const result = { ...fields }
  for (const [key, value] of Object.entries(result)) {
    if (typeof value === 'string' && value.trim() === '' && key !== 'firstName') {
      ;(result as Record<string, unknown>)[key] = null
    }
  }
  return result
}
