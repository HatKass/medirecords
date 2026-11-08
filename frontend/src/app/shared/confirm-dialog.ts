import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';

/** Small modal for destructive confirmations. Escape or the backdrop cancels. */
@Component({
  selector: 'mr-confirm-dialog',
  host: { '(document:keydown.escape)': 'busy() || cancelled.emit()' },
  template: `
    <div class="fixed inset-0 z-50 flex items-end justify-center bg-slate-900/40 p-4 sm:items-center" (click)="busy() || cancelled.emit()">
      <div class="w-full max-w-md rounded-2xl bg-white p-5 shadow-xl sm:p-6" role="alertdialog" aria-modal="true"
           aria-labelledby="confirm-title" aria-describedby="confirm-message" (click)="$event.stopPropagation()">
        <h2 id="confirm-title" class="text-lg font-semibold">{{ title() }}</h2>
        <p id="confirm-message" class="mt-2 text-sm text-slate-600">{{ message() }}</p>
        <div class="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end sm:gap-3">
          <button #cancelButton type="button" (click)="cancelled.emit()" [disabled]="busy()"
                  class="rounded-lg border border-slate-300 px-4 py-2.5 text-sm sm:py-2 font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50">
            Cancel
          </button>
          <button type="button" (click)="confirmed.emit()" [disabled]="busy()"
                  class="rounded-lg bg-red-600 px-4 py-2.5 text-sm sm:py-2 font-semibold text-white hover:bg-red-700 disabled:opacity-60">
            {{ busy() ? 'Working…' : confirmLabel() }}
          </button>
        </div>
      </div>
    </div>
  `,
})
export class ConfirmDialog {
  readonly title = input.required<string>();
  readonly message = input.required<string>();
  readonly confirmLabel = input('Confirm');
  readonly busy = input(false);

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  private readonly cancelButton = viewChild.required<ElementRef<HTMLButtonElement>>('cancelButton');

  constructor() {
    // Focus the safe choice first.
    afterNextRender(() => this.cancelButton().nativeElement.focus());
  }
}
