ALTER TABLE public.app_users DROP CONSTRAINT app_users_registration_state_check;
ALTER TABLE public.app_users ADD CONSTRAINT app_users_registration_state_check
    CHECK (registration_state IN ('LEGACY', 'PENDING_EMAIL_VERIFICATION', 'ACTIVE', 'DELETED'));

CREATE TABLE public.account_deletion_requests (
    id uuid NOT NULL,
    user_id bigint NOT NULL,
    source varchar(20) NOT NULL,
    status varchar(32) NOT NULL,
    token_hash varchar(64),
    requested_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    completed_at timestamp with time zone,
    action_code varchar(80),
    CONSTRAINT account_deletion_requests_pkey PRIMARY KEY (id),
    CONSTRAINT account_deletion_requests_token_hash_key UNIQUE (token_hash),
    CONSTRAINT account_deletion_requests_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_users(id),
    CONSTRAINT account_deletion_requests_expiry_check CHECK (expires_at IS NULL OR expires_at > requested_at)
);
CREATE INDEX account_deletion_requests_user_status_idx ON public.account_deletion_requests (user_id, status, requested_at, id);
