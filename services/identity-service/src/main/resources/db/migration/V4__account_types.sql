-- Legacy accounts remain unclassified until an operator verifies ownership across services.
-- Authentication rejects NULL; never infer SELLER from USER or default legacy accounts to BUYER.
ALTER TABLE identity.accounts ADD COLUMN account_type VARCHAR(10)
    CHECK (account_type IN ('BUYER', 'SELLER'));
