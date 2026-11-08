import { ACTION_BADGE, actionLabel, blockedMinutes, isAuthenticationEvent } from './audit.models';

describe('audit models', () => {
  it('treats a blocked sign-in as an authentication event with its own label and badge', () => {
    expect(isAuthenticationEvent('LOGIN_BLOCKED')).toBe(true);
    expect(isAuthenticationEvent('UPDATE')).toBe(false);
    expect(actionLabel('LOGIN_BLOCKED')).toBe('Sign-in blocked');
    expect(ACTION_BADGE.LOGIN_BLOCKED).toContain('rose');
  });

  it('describes the block length in whole minutes, rounded up', () => {
    expect(blockedMinutes(900)).toBe('15 minutes');
    expect(blockedMinutes(841)).toBe('15 minutes');
    expect(blockedMinutes(30)).toBe('1 minute');
    expect(blockedMinutes(undefined)).toBe('1 minute');
  });
});
