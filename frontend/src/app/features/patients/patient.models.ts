export type Gender = 'FEMALE' | 'MALE' | 'OTHER';

export type BloodType =
  | 'A_POSITIVE'
  | 'A_NEGATIVE'
  | 'B_POSITIVE'
  | 'B_NEGATIVE'
  | 'AB_POSITIVE'
  | 'AB_NEGATIVE'
  | 'O_POSITIVE'
  | 'O_NEGATIVE';

export const GENDERS: { value: Gender; label: string }[] = [
  { value: 'FEMALE', label: 'Female' },
  { value: 'MALE', label: 'Male' },
  { value: 'OTHER', label: 'Other' },
];

/** The API uses URL-safe names; people read the usual labels. */
export const BLOOD_TYPES: { value: BloodType; label: string }[] = [
  { value: 'A_POSITIVE', label: 'A+' },
  { value: 'A_NEGATIVE', label: 'A−' },
  { value: 'B_POSITIVE', label: 'B+' },
  { value: 'B_NEGATIVE', label: 'B−' },
  { value: 'AB_POSITIVE', label: 'AB+' },
  { value: 'AB_NEGATIVE', label: 'AB−' },
  { value: 'O_POSITIVE', label: 'O+' },
  { value: 'O_NEGATIVE', label: 'O−' },
];

export function bloodTypeLabel(value: BloodType | null): string {
  return BLOOD_TYPES.find((type) => type.value === value)?.label ?? '—';
}

export function genderLabel(value: Gender): string {
  return GENDERS.find((gender) => gender.value === value)?.label ?? value;
}

/** Whole years between an ISO date (yyyy-mm-dd) and today. */
export function ageFrom(dateOfBirth: string, today = new Date()): number {
  const [year, month, day] = dateOfBirth.split('-').map(Number);
  let age = today.getFullYear() - year!;
  const hadBirthday = today.getMonth() + 1 > month! || (today.getMonth() + 1 === month && today.getDate() >= day!);
  if (!hadBirthday) age--;
  return age;
}

export interface Patient {
  id: number;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: Gender;
  email: string;
  phone: string | null;
  city: string;
  bloodType: BloodType | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface PatientRequest {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: Gender;
  email: string;
  phone: string | null;
  city: string;
  bloodType: BloodType | null;
  /** Required when updating: the version that was edited (optimistic locking). */
  version?: number;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type SortField = 'lastName' | 'dateOfBirth' | 'city' | 'bloodType' | 'updatedAt';
export type SortDirection = 'asc' | 'desc';

export interface PatientQuery {
  page: number;
  size: number;
  sort: SortField;
  direction: SortDirection;
  name: string;
  city: string;
  bloodType: BloodType | '';
}
