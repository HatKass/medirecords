import { Component, inject } from '@angular/core';
import { ToastKind, ToastService } from './toast.service';

const STYLES: Record<ToastKind, string> = {
  success: 'border-emerald-200 bg-emerald-50 text-emerald-900',
  error: 'border-red-200 bg-red-50 text-red-900',
  info: 'border-slate-200 bg-white text-slate-900',
};

@Component({
  selector: 'mr-toast-container',
  template: `
    <div class="pointer-events-none fixed inset-x-4 bottom-4 z-[60] flex flex-col items-end gap-2 sm:inset-x-auto sm:right-6"
         aria-live="polite" aria-atomic="false">
      @for (toast of toasts.toasts(); track toast.id) {
        <div [class]="'pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-xl border px-4 py-3 text-sm shadow-lg ' + style(toast.kind)"
             [attr.role]="toast.kind === 'error' ? 'alert' : 'status'">
          <p class="flex-1">{{ toast.message }}</p>
          <button type="button" (click)="toasts.dismiss(toast.id)" class="-mr-1 rounded p-0.5 opacity-60 hover:opacity-100"
                  aria-label="Dismiss notification">
            <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
      }
    </div>
  `,
})
export class ToastContainer {
  protected readonly toasts = inject(ToastService);

  protected style(kind: ToastKind): string {
    return STYLES[kind];
  }
}
