import { Component, inject } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';

/** Landing page after sign-in. Summary cards and the chart arrive in Phase 6. */
@Component({
  selector: 'mr-dashboard-page',
  template: `
    <h1 class="text-2xl font-semibold tracking-tight">Welcome, {{ auth.user()?.fullName }}</h1>
    <p class="mt-1 text-sm text-slate-500">Here is what your account can do.</p>

    <div class="mt-6 max-w-2xl rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <h2 class="text-sm font-medium uppercase tracking-wide text-slate-500">Your access</h2>
      @if (auth.isAdmin()) {
        <p class="mt-2 text-slate-700">
          You are an <strong>administrator</strong>: you can view and change patient records, run CSV imports
          and read the audit log.
        </p>
      } @else {
        <p class="mt-2 text-slate-700">
          You have <strong>read-only</strong> access: you can browse patient records and import history,
          but cannot change data.
        </p>
      }
    </div>
  `,
})
export class DashboardPage {
  protected readonly auth = inject(AuthService);
}
