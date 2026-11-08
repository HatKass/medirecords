import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

interface DemoAccount {
  username: string;
  password: string;
  role: string;
  description: string;
}

@Component({
  selector: 'mr-login-page',
  imports: [ReactiveFormsModule],
  template: `
    <main class="flex min-h-dvh items-center justify-center px-4 py-8 sm:py-12">
      <div class="w-full max-w-md">
        <div class="mb-8 flex items-center justify-center gap-3">
          <div class="flex h-11 w-11 items-center justify-center rounded-xl bg-teal-600 text-lg font-bold text-white">M</div>
          <div>
            <h1 class="text-2xl font-semibold tracking-tight">MediRecords</h1>
            <p class="text-sm text-slate-500">Patient records management</p>
          </div>
        </div>

        <div class="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
          <h2 class="text-lg font-semibold">Sign in</h2>
          <p class="mt-1 text-sm text-slate-500">Use one of the demo accounts below.</p>

          @if (reason() === 'expired') {
            <p class="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800" role="status">
              Your session has expired. Please sign in again.
            </p>
          }
          @if (error()) {
            <p class="mt-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">
              {{ error() }}
            </p>
          }

          <form [formGroup]="form" (ngSubmit)="submit()" class="mt-6 space-y-4" novalidate>
            <div>
              <label for="username" class="block text-sm font-medium text-slate-700">Username</label>
              <input
                id="username"
                type="text"
                formControlName="username"
                autocomplete="username"
                class="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-600/20"
              />
              @if (form.controls.username.touched && form.controls.username.invalid) {
                <p class="mt-1 text-xs text-red-600">Username is required.</p>
              }
            </div>
            <div>
              <label for="password" class="block text-sm font-medium text-slate-700">Password</label>
              <input
                id="password"
                type="password"
                formControlName="password"
                autocomplete="current-password"
                class="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-600/20"
              />
              @if (form.controls.password.touched && form.controls.password.invalid) {
                <p class="mt-1 text-xs text-red-600">Password is required.</p>
              }
            </div>
            <button
              type="submit"
              [disabled]="submitting()"
              class="flex w-full items-center justify-center rounded-lg bg-teal-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-teal-700 focus:outline-none focus:ring-2 focus:ring-teal-600/40 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {{ submitting() ? 'Signing in…' : 'Sign in' }}
            </button>
          </form>
        </div>

        <section class="mt-6 rounded-2xl border border-dashed border-slate-300 bg-white/60 p-5" aria-labelledby="demo-title">
          <h3 id="demo-title" class="text-xs font-semibold uppercase tracking-wide text-slate-500">Demo credentials</h3>
          <ul class="mt-3 space-y-2">
            @for (account of demoAccounts; track account.username) {
              <li class="flex items-center justify-between gap-3 rounded-lg bg-slate-50 px-3 py-2">
                <div class="min-w-0">
                  <p class="flex flex-wrap items-center gap-x-1 gap-y-1 text-sm">
                    <span class="font-mono font-medium">{{ account.username }}</span>
                    <span class="text-slate-400"> / </span>
                    <span class="font-mono">{{ account.password }}</span>
                    <span class="ml-1 rounded-full bg-slate-200 px-2 py-0.5 text-xs font-medium text-slate-700">{{ account.role }}</span>
                  </p>
                  <p class="text-xs text-slate-500">{{ account.description }}</p>
                </div>
                <button
                  type="button"
                  (click)="useAccount(account)"
                  class="shrink-0 rounded-md border border-slate-300 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100"
                >
                  Use
                </button>
              </li>
            }
          </ul>
        </section>
      </div>
    </main>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Bound from the query string (?returnUrl=/patients&reason=expired). */
  readonly returnUrl = input<string>();
  readonly reason = input<string>();

  protected readonly submitting = signal(false);
  protected readonly error = signal('');

  protected readonly demoAccounts: DemoAccount[] = [
    { username: 'admin', password: 'demo123', role: 'ADMIN', description: 'Full access: create, edit, import, audit log' },
    { username: 'viewer', password: 'demo123', role: 'VIEWER', description: 'Read-only access' },
  ];

  protected readonly form = inject(NonNullableFormBuilder).group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  protected useAccount(account: DemoAccount): void {
    this.form.setValue({ username: account.username, password: account.password });
    this.error.set('');
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set('');
    const { username, password } = this.form.getRawValue();
    this.auth.login(username.trim(), password).subscribe({
      next: () => void this.router.navigateByUrl(safeReturnUrl(this.returnUrl())),
      error: (error: HttpErrorResponse) => {
        this.submitting.set(false);
        this.error.set(loginErrorMessage(error));
      },
    });
  }
}

/** Only same-app paths are allowed, so a crafted link cannot redirect somewhere else after login. */
function safeReturnUrl(url: string | undefined): string {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/login') ? url : '/';
}

function loginErrorMessage(error: HttpErrorResponse): string {
  if (error.status === 0) return 'Cannot reach the server. Is the backend running?';
  if (error.status === 401) return error.error?.detail ?? 'Invalid username or password.';
  if (error.status === 429) return error.error?.detail ?? 'Too many failed sign-in attempts. Try again later.';
  return 'Sign-in failed. Please try again.';
}
