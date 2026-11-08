import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in · MediRecords',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login-page').then((m) => m.LoginPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Dashboard · MediRecords',
        loadComponent: () => import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
      },
      {
        path: 'patients',
        title: 'Patients · MediRecords',
        loadComponent: () => import('./features/patients/patients-page').then((m) => m.PatientsPage),
      },
      {
        path: 'import',
        title: 'Import patients · MediRecords',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () => import('./features/imports/import-upload-page').then((m) => m.ImportUploadPage),
      },
      {
        path: 'imports',
        title: 'Import history · MediRecords',
        loadComponent: () => import('./features/imports/import-history-page').then((m) => m.ImportHistoryPage),
      },
      {
        path: 'imports/:id',
        title: 'Import details · MediRecords',
        loadComponent: () => import('./features/imports/import-detail-page').then((m) => m.ImportDetailPage),
      },
      {
        path: 'audit-log',
        title: 'Audit log · MediRecords',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () => import('./features/audit/audit-log-page').then((m) => m.AuditLogPage),
      },
      {
        path: 'status',
        title: 'System status · MediRecords',
        loadComponent: () => import('./features/status/status-page').then((m) => m.StatusPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
