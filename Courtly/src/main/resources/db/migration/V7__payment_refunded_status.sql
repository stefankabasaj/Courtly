-- V7 - payments can be REFUNDED (an admin cancelled a paid booking).
-- V1 declared the status check inline, so Postgres named it itself. Drop every
-- check on payments that mentions status, whatever it's called, then add one
-- with a name of our own.
DO $$
DECLARE
    c text;
BEGIN
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE  conrelid = 'courtly.payments'::regclass
          AND  contype  = 'c'
          AND  pg_get_constraintdef(oid) LIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE courtly.payments DROP CONSTRAINT %I', c);
    END LOOP;
END $$;

ALTER TABLE courtly.payments
    ADD CONSTRAINT chk_payment_status
    CHECK (status IN ('PENDING','SUCCESS','FAILED','REFUNDED'));
