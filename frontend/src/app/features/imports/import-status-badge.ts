import { Component, computed, input } from '@angular/core';
import { ImportStatus } from './import.models';

const STYLES: Record<ImportStatus, { label: string; classes: string }> = {
  QUEUED: { label: 'Queued', classes: 'bg-slate-100 text-slate-700' },
  RUNNING: { label: 'Running', classes: 'bg-sky-100 text-sky-800' },
  COMPLETED: { label: 'Completed', classes: 'bg-emerald-100 text-emerald-800' },
  FAILED: { label: 'Failed', classes: 'bg-red-100 text-red-800' },
};

@Component({
  selector: 'mr-import-status-badge',
  template: `
    <span [class]="'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold ' + style().classes">
      @if (status() === 'RUNNING' || status() === 'QUEUED') {
        <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-current" aria-hidden="true"></span>
      }
      {{ style().label }}
    </span>
  `,
})
export class ImportStatusBadge {
  readonly status = input.required<ImportStatus>();
  protected readonly style = computed(() => STYLES[this.status()]);
}
