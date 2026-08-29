ALTER TABLE public.orders ADD COLUMN void_reason varchar(500);

ALTER TABLE public.orders ADD COLUMN voided_at timestamp with time zone;

ALTER TABLE public.orders ADD COLUMN voided_by_user_id bigint;

ALTER TABLE public.orders
    ADD CONSTRAINT orders_voided_by_user_id_fkey
        FOREIGN KEY (voided_by_user_id) REFERENCES public.app_users(id);

ALTER TABLE public.orders
    ADD CONSTRAINT orders_void_audit_consistency_check
        CHECK (
            (void_reason IS NULL AND voided_at IS NULL AND voided_by_user_id IS NULL)
            OR
            (status = 'VOIDED'
                AND order_source IS NOT NULL
                AND order_source IN ('ANDROID_MANUAL', 'COUNTER')
                AND void_reason IS NOT NULL
                AND trim(void_reason) <> ''
                AND voided_at IS NOT NULL
                AND voided_by_user_id IS NOT NULL)
        );
