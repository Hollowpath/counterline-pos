import { postJson, putJson } from '../../api/http'
import { customerPaths, type Customer, type CustomerFields } from './types'

export function createCustomer(fields: CustomerFields): Promise<Customer> {
  return postJson<Customer>('/api/v1/customers', fields)
}

/** `version` is the one the form was loaded with; the server answers 409 if someone saved since. */
export function updateCustomer(id: number, version: number, fields: CustomerFields): Promise<Customer> {
  return putJson<Customer>(customerPaths.customer(id), { ...fields, version })
}

/** A manager's: the member keeps their identity, points and orders but cannot be used on a sale. */
export function deactivateCustomer(id: number): Promise<Customer> {
  return postJson<Customer>(customerPaths.deactivate(id), {})
}

export function reactivateCustomer(id: number): Promise<Customer> {
  return postJson<Customer>(customerPaths.reactivate(id), {})
}
