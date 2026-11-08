import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Role } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';

interface NavItem {
  label: string;
  path: string;
  /** SVG path data for a 24x24 outline icon. */
  icon: string;
  /** Roles that see the item; omitted means every signed-in user. */
  roles?: Role[];
}

const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', path: '/', icon: 'M3 12l9-9 9 9M5 10v10h5v-6h4v6h5V10' },
  {
    label: 'Patients',
    path: '/patients',
    icon: 'M16 19v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1M9.5 10a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M21 19v-1a4 4 0 0 0-3-3.87M15.5 3.13a3.5 3.5 0 0 1 0 6.74',
  },
  {
    label: 'Import',
    path: '/import',
    icon: 'M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3',
    roles: ['ADMIN'],
  },
  { label: 'Import history', path: '/imports', icon: 'M12 8v4l3 2M3.05 11a9 9 0 1 1 .5 4M3 4v5h5' },
  {
    label: 'Audit log',
    path: '/audit-log',
    icon: 'M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2M9 13l2 2 4-4',
    roles: ['ADMIN'],
  },
  { label: 'System status', path: '/status', icon: 'M4 13h4l3-8 3 14 3-6h3' },
];

/** Authenticated layout: sidebar navigation (collapsible on small screens) and a top bar. */
@Component({
  selector: 'mr-shell',
  host: { '(document:keydown.escape)': 'menuOpen.set(false)' },
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="min-h-dvh lg:flex">
      @if (menuOpen()) {
        <div class="fixed inset-0 z-30 bg-slate-900/40 lg:hidden" (click)="menuOpen.set(false)" aria-hidden="true"></div>
      }

      <aside
        class="fixed inset-y-0 left-0 z-40 flex w-64 max-w-[85vw] flex-col border-r border-slate-200 bg-white transition-[transform,visibility] lg:visible lg:static lg:max-w-none lg:translate-x-0"
        [class.-translate-x-full]="!menuOpen()"
        [class.invisible]="!menuOpen()"
        aria-label="Main navigation"
      >
        <div class="flex h-16 items-center gap-3 border-b border-slate-200 px-5">
          <div class="flex h-9 w-9 items-center justify-center rounded-lg bg-teal-600 font-bold text-white">M</div>
          <span class="text-lg font-semibold tracking-tight">MediRecords</span>
          <button type="button" (click)="menuOpen.set(false)" class="ml-auto rounded-lg p-2 text-slate-500 hover:bg-slate-100 lg:hidden" aria-label="Close navigation">
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
        <nav class="flex-1 space-y-1 overflow-y-auto p-3">
          @for (item of navItems(); track item.path) {
            <a
              [routerLink]="item.path"
              routerLinkActive="bg-teal-50 text-teal-800"
              [routerLinkActiveOptions]="{ exact: item.path === '/' }"
              (click)="menuOpen.set(false)"
              class="flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900"
            >
              <svg class="h-5 w-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                   stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path [attr.d]="item.icon" />
              </svg>
              {{ item.label }}
            </a>
          }
        </nav>
        <p class="border-t border-slate-200 px-5 py-3 text-xs text-slate-400">Demo app · fictional data</p>
      </aside>

      <div class="flex min-w-0 flex-1 flex-col">
        <header class="sticky top-0 z-20 flex h-16 items-center justify-between gap-4 border-b border-slate-200 bg-white/90 px-4 backdrop-blur sm:px-6">
          <div class="flex min-w-0 items-center gap-2 lg:hidden">
            <button
              type="button"
              class="-ml-2 rounded-lg p-2 text-slate-600 hover:bg-slate-100"
              (click)="menuOpen.set(true)"
              aria-label="Open navigation"
              [attr.aria-expanded]="menuOpen()"
            >
              <svg class="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
                <path d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>
            <span class="hidden truncate font-semibold tracking-tight min-[400px]:inline">MediRecords</span>
          </div>
          <div class="hidden lg:block"></div>

          @if (auth.user(); as user) {
            <div class="flex shrink-0 items-center gap-2 sm:gap-3">
              <p class="hidden text-sm font-medium sm:block" [title]="'Signed in as ' + user.username">{{ user.fullName }}</p>
              <span [class]="roleBadgeClass()">{{ user.roles.join(', ') }}</span>
              <div class="hidden h-9 w-9 items-center justify-center rounded-full sm:flex bg-slate-200 text-sm font-semibold text-slate-700" aria-hidden="true">
                {{ initials() }}
              </div>
              <button
                type="button"
                (click)="auth.logout()"
                class="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100"
              >
                Log out
              </button>
            </div>
          }
        </header>

        <main class="flex-1 p-4 sm:p-6 lg:p-8">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly menuOpen = signal(false);

  protected readonly navItems = computed(() =>
    NAV_ITEMS.filter((item) => !item.roles || this.auth.hasRole(...item.roles)),
  );

  protected readonly initials = computed(() =>
    (this.auth.user()?.fullName ?? '')
      .replace(/^Dr\.?\s+/i, '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]!.toUpperCase())
      .join(''),
  );

  protected readonly roleBadgeClass = computed(() => {
    const base = 'rounded-full px-2.5 py-0.5 text-xs font-semibold';
    return this.auth.isAdmin() ? `${base} bg-teal-100 text-teal-800` : `${base} bg-slate-100 text-slate-700`;
  });
}
