import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./features/status/status-page').then((m) => m.StatusPage) },
  { path: '**', redirectTo: '' },
];
