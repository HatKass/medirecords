import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastContainer } from './core/toast/toast-container';

@Component({
  selector: 'mr-root',
  imports: [RouterOutlet, ToastContainer],
  template: '<router-outlet /><mr-toast-container />',
})
export class App {}
