-- Manual, explicit promotion of one existing ACTIVE account; no account/default password is created.
\set ON_ERROR_STOP on
BEGIN;
SELECT id AS target_id FROM identity.accounts
WHERE email = lower(btrim(:'admin_email')) AND status = 'ACTIVE' AND account_type IS NOT NULL
FOR UPDATE
\gset
INSERT INTO identity.account_roles(account_id, role) VALUES (:'target_id'::uuid, 'ADMIN')
ON CONFLICT (account_id, role) DO NOTHING;
COMMIT;
