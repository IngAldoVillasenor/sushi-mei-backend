ALTER TABLE app_users DROP CONSTRAINT app_users_registration_state_check;
ALTER TABLE app_users ADD CONSTRAINT app_users_registration_state_check
    CHECK (registration_state IN ('LEGACY', 'PENDING_EMAIL_VERIFICATION', 'ACTIVE', 'DELETED'));

CREATE TABLE account_deletion_requests (
    id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    action_code VARCHAR(80),
    CONSTRAINT account_deletion_requests_pkey PRIMARY KEY (id),
    CONSTRAINT account_deletion_requests_token_hash_key UNIQUE (token_hash),
    CONSTRAINT account_deletion_requests_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT account_deletion_requests_expiry_check CHECK (expires_at IS NULL OR expires_at > requested_at)
);
CREATE INDEX account_deletion_requests_user_status_idx ON account_deletion_requests (user_id, status, requested_at, id);
