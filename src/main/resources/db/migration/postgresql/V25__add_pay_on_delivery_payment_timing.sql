ALTER TABLE public.orders
    ADD COLUMN payment_timing varchar(16) NOT NULL DEFAULT 'IMMEDIATE',
    ADD COLUMN payment_collected_at timestamp with time zone,
    ADD COLUMN payment_collected_by_user_id bigint;

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_payment_collected_by_user_id_fkey
        FOREIGN KEY (payment_collected_by_user_id) REFERENCES public.app_users(id),
    ADD CONSTRAINT orders_payment_timing_check
        CHECK (payment_timing IN ('IMMEDIATE', 'ON_DELIVERY')),
    ADD CONSTRAINT orders_pay_on_delivery_payment_consistency_check
        CHECK (
            (payment_timing = 'IMMEDIATE'
                AND payment_collected_at IS NULL
                AND payment_collected_by_user_id IS NULL)
            OR
            (payment_timing = 'ON_DELIVERY'
                AND order_source IS NOT NULL
                AND order_source IN ('ANDROID_MANUAL', 'COUNTER')
                AND fulfillment_type IS NOT NULL
                AND fulfillment_type = 'DELIVERY'
                AND (
                    (payment_method IS NULL
                        AND cash_denomination IS NULL
                        AND payment_collected_at IS NULL
                        AND payment_collected_by_user_id IS NULL
                        AND status IS NOT NULL
                        AND status <> 'COMPLETED')
                    OR
                    (status = 'COMPLETED'
                        AND payment_method IN ('CASH', 'TRANSFER', 'CARD')
                        AND payment_collected_at IS NOT NULL
                        AND payment_collected_by_user_id IS NOT NULL
                        AND (
                            (payment_method = 'CASH' AND cash_denomination IS NOT NULL)
                            OR (payment_method IN ('TRANSFER', 'CARD') AND cash_denomination IS NULL)
                        ))
                ))
        );
