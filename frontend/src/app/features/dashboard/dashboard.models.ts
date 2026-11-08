import { AuditAction } from '../audit/audit.models';
import { ImportStatus } from '../imports/import.models';
import { BloodType } from '../patients/patient.models';

export interface BloodTypeCount {
  /** null: blood type not recorded. */
  bloodType: BloodType | null;
  count: number;
}

export interface PatientSummary {
  total: number;
  /** Patients created in the window (last 7 days). */
  addedSince: number;
  /** Every blood type in a fixed order (zeros included), then "not recorded" if there are any. */
  byBloodType: BloodTypeCount[];
}

/** Imports created in the window (last 7 days). */
export interface ImportSummary {
  count: number;
  failedImports: number;
  inProgress: number;
  validRows: number;
  /** Rows rejected by validation: the "failed rows" of the error reports. */
  invalidRows: number;
}

export interface RecentImport {
  id: number;
  fileName: string;
  status: ImportStatus;
  importedBy: string;
  validRows: number;
  invalidRows: number;
  createdAt: string;
}

export interface RecentActivity {
  id: number;
  occurredAt: string;
  actor: string;
  action: AuditAction;
  entityType: string;
  entityId: number | null;
  summary: string;
}

export interface Dashboard {
  generatedAt: string;
  /** Start of the rolling 7-day window. */
  since: string;
  patients: PatientSummary;
  imports: ImportSummary;
  recentImports: RecentImport[];
  /** Admins only: absent from the response for viewers. */
  recentActivity?: RecentActivity[];
}

/** Share of rejected rows among all rows processed, as a whole percentage; null when nothing was processed. */
export function rejectionRate(imports: ImportSummary): number | null {
  const processed = imports.validRows + imports.invalidRows;
  return processed === 0 ? null : Math.round((imports.invalidRows * 100) / processed);
}

/** Bar width in percent of the largest group, so the tallest bar fills the track. */
export function barWidth(count: number, groups: BloodTypeCount[]): number {
  const max = Math.max(0, ...groups.map((group) => group.count));
  return max === 0 ? 0 : (count * 100) / max;
}
