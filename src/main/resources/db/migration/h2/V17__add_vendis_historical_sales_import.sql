ALTER TABLE public.orders
    DROP CONSTRAINT orders_total_amount_amount_positive_check;
ALTER TABLE public.orders
    DROP CONSTRAINT orders_money_representations_agree_check;
ALTER TABLE public.orders
    ADD CONSTRAINT orders_total_amount_amount_by_source_check
        CHECK (
            (coalesce(order_source, '') = 'VENDIS_IMPORT' AND total_amount_amount >= 0)
            OR total_amount_amount > 0
        );
ALTER TABLE public.orders
    ADD CONSTRAINT orders_money_representations_agree_check
        CHECK (
            total_amount IS NULL
            OR (
                CASE
                    WHEN total_amount = CAST('NaN' AS DOUBLE PRECISION)
                      OR total_amount = CAST('Infinity' AS DOUBLE PRECISION)
                      OR total_amount = CAST('-Infinity' AS DOUBLE PRECISION)
                    THEN FALSE
                    ELSE CAST(total_amount AS NUMERIC) = total_amount_amount
                     AND CAST(total_amount_amount AS DOUBLE PRECISION) = total_amount
                END
            )
        );
ALTER TABLE public.orders
    ADD CONSTRAINT orders_id_order_source_key UNIQUE (id, order_source);

ALTER TABLE public.order_lines
    ADD COLUMN parent_order_source varchar(32);
ALTER TABLE public.order_lines
    ADD COLUMN external_historical boolean NOT NULL DEFAULT false;
ALTER TABLE public.order_lines
    ADD COLUMN external_product_detail text;
ALTER TABLE public.order_lines
    ADD COLUMN source_unit_price_amount numeric(19,4);
ALTER TABLE public.order_lines
    ADD COLUMN source_line_total_amount numeric(19,4);
ALTER TABLE public.order_lines
    ADD COLUMN source_discount_amount numeric(19,4);
ALTER TABLE public.order_lines
    ADD COLUMN source_discount_percentage numeric(19,4);
ALTER TABLE public.order_lines
    ADD COLUMN source_tax_amount numeric(19,4);
ALTER TABLE public.order_lines
    ADD COLUMN source_price_including_tax_amount numeric(19,4);

UPDATE public.order_lines
SET parent_order_source = (
    SELECT orders.order_source FROM public.orders orders WHERE orders.id = public.order_lines.order_id
);

ALTER TABLE public.order_lines
    DROP CONSTRAINT order_lines_line_total_amount_matches_check;
ALTER TABLE public.order_lines
    DROP CONSTRAINT order_lines_money_by_kind_check;
ALTER TABLE public.order_lines
    DROP CONSTRAINT order_lines_provenance_check;
ALTER TABLE public.order_lines
    ADD CONSTRAINT order_lines_parent_order_source_fkey
        FOREIGN KEY (order_id, parent_order_source)
        REFERENCES public.orders (id, order_source);
ALTER TABLE public.order_lines
    ADD CONSTRAINT order_lines_historical_parent_source_check
        CHECK (
            NOT external_historical
            OR (
                line_kind = 'PAID'
                AND parent_order_source IS NOT NULL
                AND parent_order_source = 'VENDIS_IMPORT'
            )
        );
ALTER TABLE public.order_lines
    ADD CONSTRAINT order_lines_line_total_amount_matches_check
        CHECK (external_historical OR line_total_amount = quantity * unit_price_amount);
ALTER TABLE public.order_lines
    ADD CONSTRAINT order_lines_money_by_kind_check
        CHECK (
            (external_historical AND line_kind = 'PAID'
                AND unit_price_amount >= 0 AND line_total_amount >= 0)
            OR
            (NOT external_historical AND line_kind = 'PAID'
                AND unit_price_amount > 0 AND line_total_amount > 0)
            OR
            (NOT external_historical AND line_kind = 'PROMOTION_REWARD'
                AND unit_price_amount >= 0 AND line_total_amount >= 0)
        );
ALTER TABLE public.order_lines
    ADD CONSTRAINT order_lines_provenance_check
        CHECK (
            (line_kind = 'PAID' AND (
                source_cart_item_id IS NOT NULL
                OR source_menu_item_id IS NOT NULL
                OR (external_product_reference IS NOT NULL AND trim(external_product_reference) <> '')
                OR external_historical
            ))
            OR
            (line_kind = 'PROMOTION_REWARD'
                AND applied_promotion_id IS NOT NULL
                AND applied_promotion_benefit_type IS NOT NULL)
        );
ALTER TABLE public.order_lines
    ADD CONSTRAINT order_lines_historical_source_values_nonnegative_check
        CHECK (
            (source_unit_price_amount IS NULL OR source_unit_price_amount >= 0)
            AND (source_line_total_amount IS NULL OR source_line_total_amount >= 0)
            AND (source_discount_amount IS NULL OR source_discount_amount >= 0)
            AND (source_discount_percentage IS NULL OR source_discount_percentage >= 0)
            AND (source_tax_amount IS NULL OR source_tax_amount >= 0)
            AND (source_price_including_tax_amount IS NULL OR source_price_including_tax_amount >= 0)
        );
ALTER TABLE public.order_lines
    ADD CONSTRAINT order_lines_historical_source_required_check
        CHECK (
            NOT external_historical
            OR (source_unit_price_amount IS NOT NULL AND source_line_total_amount IS NOT NULL)
        );

CREATE TABLE public.vendis_order_snapshots (
    order_id bigint NOT NULL,
    detail_payment_status varchar(120),
    summary_payment_status_raw varchar(120),
    vendis_status varchar(120),
    customer_name varchar(255),
    total_before_tax numeric(19,4),
    final_total_source numeric(19,4) NOT NULL,
    discount_amount numeric(19,4),
    discount_type varchar(32),
    is_revocate integer NOT NULL,
    contact_id varchar(120),
    contact_name varchar(255),
    business_location_name varchar(255),
    total_paid numeric(19,4),
    total_debt numeric(19,4),
    computed_line_subtotal numeric(19,4),
    computed_payments_total numeric(19,4),
    sale_reconciliation_difference numeric(19,4),
    payment_reconciliation_difference numeric(19,4),
    CONSTRAINT vendis_order_snapshots_pkey PRIMARY KEY (order_id),
    CONSTRAINT vendis_order_snapshots_order_id_fkey
        FOREIGN KEY (order_id) REFERENCES public.orders(id),
    CONSTRAINT vendis_order_snapshots_final_total_nonnegative_check
        CHECK (final_total_source >= 0),
    CONSTRAINT vendis_order_snapshots_discount_nonnegative_check
        CHECK (discount_amount IS NULL OR discount_amount >= 0)
);

CREATE TABLE public.vendis_payment_snapshots (
    id bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    order_id bigint NOT NULL,
    position integer NOT NULL,
    payment_date_raw varchar(80),
    payment_reference varchar(255),
    amount numeric(19,4) NOT NULL,
    payment_method_raw varchar(120),
    note text,
    CONSTRAINT vendis_payment_snapshots_pkey PRIMARY KEY (id),
    CONSTRAINT vendis_payment_snapshots_order_id_fkey
        FOREIGN KEY (order_id) REFERENCES public.orders(id),
    CONSTRAINT vendis_payment_snapshots_order_position_key UNIQUE (order_id, position),
    CONSTRAINT vendis_payment_snapshots_position_positive_check CHECK (position > 0),
    CONSTRAINT vendis_payment_snapshots_amount_nonnegative_check CHECK (amount >= 0)
);

CREATE INDEX vendis_payment_snapshots_order_id_idx
    ON public.vendis_payment_snapshots (order_id, position);
