-- V10: sign-ins refused by the login rate limiter (LOGIN_BLOCKED), recorded once per block.
-- details holds the client address and the wait: {"clientIp": "203.0.113.7", "retryAfterSeconds": 840}.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'IMPORT',
                      'LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGIN_BLOCKED', 'LOGOUT'));
