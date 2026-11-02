ALTER TABLE merchant.audit_entries ADD COLUMN actor_display_name TEXT;
ALTER TABLE merchant.audit_entries ADD COLUMN version_after BIGINT;
ALTER TABLE merchant.audit_entries ADD CONSTRAINT moderation_audit_complete CHECK (
    action <> 'CHANGE_SHOP_STATUS' OR (
        actor_display_name IS NOT NULL AND char_length(actor_display_name) BETWEEN 1 AND 100
        AND version_after IS NOT NULL AND version_after BETWEEN 1 AND 9007199254740991
        AND previous_status IS NOT NULL AND previous_status IN ('ACTIVE','SUSPENDED')
        AND next_status IN ('ACTIVE','SUSPENDED') AND previous_status <> next_status
        AND reason IS NOT NULL AND char_length(btrim(reason)) BETWEEN 10 AND 1000
    )
);
CREATE UNIQUE INDEX shop_status_version ON merchant.audit_entries(shop_id,version_after) WHERE action = 'CHANGE_SHOP_STATUS';
CREATE INDEX shop_status_history_page ON merchant.audit_entries(shop_id,created_at DESC,id DESC) WHERE action = 'CHANGE_SHOP_STATUS';
