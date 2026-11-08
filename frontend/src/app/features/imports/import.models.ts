export type ImportStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface PatientImport {
  id: number;
  /** Spring Batch job execution id; null until the job has started. */
  jobExecutionId: number | null;
  fileName: string;
  status: ImportStatus;
  importedBy: string;
  /** Data lines counted at upload (drives the progress bar). */
  expectedRows: number;
  /** Rows processed so far: valid + invalid. */
  totalRows: number;
  validRows: number;
  invalidRows: number;
  progressPercent: number;
  failureMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface ImportRowError {
  rowNumber: number;
  reason: string;
  rawLine: string;
}

export function isFinished(status: ImportStatus): boolean {
  return status === 'COMPLETED' || status === 'FAILED';
}

/** "1.2 s", "3 min 05 s"; null while the import has not started or finished. */
export function importDuration(item: PatientImport): string | null {
  if (!item.startedAt || !item.finishedAt) return null;
  const ms = new Date(item.finishedAt).getTime() - new Date(item.startedAt).getTime();
  if (ms < 60_000) return `${(Math.max(ms, 0) / 1000).toFixed(1)} s`;
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)} min ${String(seconds % 60).padStart(2, '0')} s`;
}

export const REQUIRED_COLUMNS = ['firstName', 'lastName', 'dateOfBirth', 'gender', 'email', 'city'];
export const OPTIONAL_COLUMNS = ['phone', 'bloodType'];

/** Small example file offered on the import page. Uses fictional data only. */
export const TEMPLATE_CSV = [
  [...REQUIRED_COLUMNS, ...OPTIONAL_COLUMNS].join(','),
  'Jane,Doe,1985-04-12,F,jane.doe@example.com,London,+44 7700 900123,A+',
  'Omar,Haddad,23/11/1972,M,omar.haddad@example.org,Leeds,,O-',
].join('\r\n');
