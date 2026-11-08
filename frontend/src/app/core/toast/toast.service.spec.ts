import { TestBed } from '@angular/core/testing';
import { ToastService } from './toast.service';

describe('ToastService', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('shows toasts and dismisses them automatically', () => {
    const toasts = TestBed.inject(ToastService);

    toasts.success('Saved');
    toasts.error('Failed');
    expect(toasts.toasts().map((toast) => toast.message)).toEqual(['Saved', 'Failed']);

    vi.advanceTimersByTime(4000);
    expect(toasts.toasts().map((toast) => toast.message)).toEqual(['Failed']); // errors stay longer

    vi.advanceTimersByTime(3000);
    expect(toasts.toasts()).toEqual([]);
  });

  it('keeps at most four on screen', () => {
    const toasts = TestBed.inject(ToastService);
    for (let i = 1; i <= 6; i++) toasts.info(`#${i}`);
    expect(toasts.toasts().map((toast) => toast.message)).toEqual(['#3', '#4', '#5', '#6']);
  });
});
