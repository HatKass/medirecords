import { HttpErrorResponse } from '@angular/common/http';
import { errorMessage } from './problem';

describe('errorMessage', () => {
  it('uses the ProblemDetail detail from the API', () => {
    const error = new HttpErrorResponse({ status: 409, error: { status: 409, detail: 'Email already exists.' } });
    expect(errorMessage(error)).toBe('Email already exists.');
  });

  it('explains a network failure instead of showing a generic error', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toContain('Cannot reach the server');
  });

  it('falls back when the body is not a ProblemDetail (e.g. an HTML proxy error page)', () => {
    const error = new HttpErrorResponse({ status: 502, error: '<html>Bad gateway</html>' });
    expect(errorMessage(error, 'Could not save.')).toBe('Could not save.');
    expect(errorMessage(new Error('boom'))).toBe('Something went wrong. Please try again.');
  });
});
