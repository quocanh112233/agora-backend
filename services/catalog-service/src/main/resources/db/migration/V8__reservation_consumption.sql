ALTER TABLE catalog.reservations ADD COLUMN consumed_capture_id uuid UNIQUE;
ALTER TABLE catalog.reservations ADD COLUMN consumed_at timestamptz;
-- V6 generated names for multi-column checks; replace only checks involving state.
DO $$ DECLARE c record; BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='catalog.reservations'::regclass AND contype='c' AND pg_get_constraintdef(oid) ~ '\mstate\M' LOOP
        EXECUTE format('ALTER TABLE catalog.reservations DROP CONSTRAINT %I',c.conname);
    END LOOP;
END $$;
ALTER TABLE catalog.reservations ADD CONSTRAINT reservations_state_check CHECK(state IN ('HELD','RELEASED','CONSUMED'));
ALTER TABLE catalog.reservations ADD CONSTRAINT reservations_terminal_metadata CHECK(
    (state='HELD' AND released_at IS NULL AND release_reason IS NULL AND consumed_capture_id IS NULL AND consumed_at IS NULL)
 OR (state='RELEASED' AND released_at IS NOT NULL AND released_at>=created_at AND release_reason IN ('CANCELLED','EXPIRED','UNAVAILABLE') AND release_reason IS NOT NULL AND consumed_capture_id IS NULL AND consumed_at IS NULL)
 OR (state='CONSUMED' AND released_at IS NULL AND release_reason IS NULL AND consumed_capture_id IS NOT NULL AND consumed_at IS NOT NULL AND consumed_at>=created_at AND consumed_at<expires_at AND accepted_not_after IS NOT NULL)
);
ALTER TABLE catalog.reservation_audit DROP CONSTRAINT reservation_audit_action_check;
ALTER TABLE catalog.reservation_audit ADD CONSTRAINT reservation_audit_action_check CHECK(action IN ('HOLD','RELEASE','EXPIRE','CONSUME'));
