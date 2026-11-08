import { BloodType, Gender, bloodTypeLabel, genderLabel } from '../patients/patient.models';

export type AuditAction = 'CREATE' | 'UPDATE' | 'DELETE' | 'IMPORT' | 'LOGIN_SUCCESS' | 'LOGIN_FAILED' | 'LOGOUT';

export const AUDIT_ACTIONS: { value: AuditAction; label: string }[] = [
  { value: 'CREATE', label: 'Created' },
  { value: 'UPDATE', label: 'Updated' },
  { value: 'DELETE', label: 'Deleted' },
  { value: 'IMPORT', label: 'Imported' },
  { value: 'LOGIN_SUCCESS', label: 'Signed in' },
  { value: 'LOGIN_FAILED', label: 'Failed sign-in' },
  { value: 'LOGOUT', label: 'Signed out' },
];

/** Sign-in events: only the username and the time are recorded. */
export function isAuthenticationEvent(action: AuditAction): boolean {
  return action === 'LOGIN_SUCCESS' || action === 'LOGIN_FAILED' || action === 'LOGOUT';
}

export interface FieldChange {
  field: string;
  /** null when the field was set by a create. */
  oldValue: string | null;
  /** null when the field was cleared, or by a delete. */
  newValue: string | null;
}

/** Facts recorded with an IMPORT entry. */
export interface ImportAuditDetails {
  fileName: string;
  status: 'COMPLETED' | 'FAILED';
  expectedRows: number;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  failureMessage?: string;
}

export interface AuditEntry {
  id: number;
  occurredAt: string;
  actor: string;
  action: AuditAction;
  entityType: string;
  /** null for sign-in events, which are not about a record. */
  entityId: number | null;
  summary: string;
  changes: FieldChange[];
  details: Partial<ImportAuditDetails> & Record<string, unknown>;
}

/** Filters as the page keeps them (and puts in the URL); dates are local calendar days, yyyy-mm-dd. */
export interface AuditQuery {
  page: number;
  actor: string;
  action: AuditAction | '';
  from: string;
  to: string;
}

/** Tailwind classes for an action's badge (used with `ring-1 ring-inset`). */
export const ACTION_BADGE: Record<AuditAction, string> = {
  CREATE: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  UPDATE: 'bg-sky-50 text-sky-700 ring-sky-600/20',
  DELETE: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  IMPORT: 'bg-violet-50 text-violet-700 ring-violet-600/20',
  LOGIN_SUCCESS: 'bg-slate-50 text-slate-700 ring-slate-500/20',
  LOGIN_FAILED: 'bg-amber-50 text-amber-800 ring-amber-600/30',
  LOGOUT: 'bg-slate-50 text-slate-500 ring-slate-400/20',
};

export function actionLabel(action: AuditAction): string {
  return AUDIT_ACTIONS.find((item) => item.value === action)?.label ?? action;
}

const FIELD_LABELS: Record<string, string> = {
  firstName: 'First name',
  lastName: 'Last name',
  dateOfBirth: 'Date of birth',
  gender: 'Gender',
  email: 'Email',
  phone: 'Phone',
  city: 'City',
  bloodType: 'Blood type',
};

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field;
}

/** Shows stored enum names the way the rest of the app does ("A_POSITIVE" → "A+"). */
export function displayValue(field: string, value: string | null): string {
  if (value === null || value === '') return '—';
  if (field === 'bloodType') return bloodTypeLabel(value as BloodType);
  if (field === 'gender') return genderLabel(value as Gender);
  return value;
}

/**
 * Turns the inclusive local-day range the user picked into the API's instants: {@code from} is the
 * start of the first day, {@code to} the start of the day after the last one (exclusive), both in the
 * browser's time zone. "Nov 8 – Nov 8" therefore means the whole of Nov 8 where the user is.
 */
export function toInstantRange(from: string, to: string): { from?: string; to?: string } {
  return {
    from: from ? startOfLocalDay(from, 0).toISOString() : undefined,
    to: to ? startOfLocalDay(to, 1).toISOString() : undefined,
  };
}

function startOfLocalDay(isoDate: string, addDays: number): Date {
  const [year, month, day] = isoDate.split('-').map(Number);
  return new Date(year!, month! - 1, day! + addDays);
}
