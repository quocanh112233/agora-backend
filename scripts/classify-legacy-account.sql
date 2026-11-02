-- Operator-only, AFTER an inventory across Identity/Merchant/Commerce/Payment/Feedback and a backup.
-- psql -v account_id=<uuid> -v account_type=BUYER|SELLER -v expected_version=<number> -f scripts/classify-legacy-account.sql
-- Only unclassified accounts qualify. Never use this to switch an existing BUYER/SELLER or split mixed ownership.
\set ON_ERROR_STOP on
BEGIN;
SELECT id AS target_id FROM identity.accounts
WHERE id = :'account_id'::uuid AND account_type IS NULL AND version = :'expected_version'::bigint
FOR UPDATE
\gset
UPDATE identity.accounts SET account_type = :'account_type', version = version + 1, updated_at = now()
WHERE id = :'target_id'::uuid;
DELETE FROM identity.spring_session WHERE principal_name = :'target_id';
COMMIT;
