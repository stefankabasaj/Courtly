-- V3 - lock the seeded admin account.
-- Its password (Admin123!) is published in the repository, so anyone could log
-- in as admin on any instance that still uses it. The account is now created
-- or unlocked at startup from ADMIN_EMAIL / ADMIN_PASSWORD (see AdminBootstrap).
--
-- 'LOCKED' is not a BCrypt hash, so no password can ever match it.
-- Only touches the account if it still has one of the two published hashes
-- (V1's broken one or V2's Admin123!), so a password that was already changed
-- is left alone.
UPDATE courtly.users
SET    password_hash = 'LOCKED',
       active        = FALSE
WHERE  email = 'admin@courtly.com'
  AND  password_hash IN (
           '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewfIoOhZv6.8xyMW',
           '$2b$12$xG3s3s5rYZgu8ornxaJnfueA17n1dKUjbfbQ08hidfqBDApWgnT4.'
       );
