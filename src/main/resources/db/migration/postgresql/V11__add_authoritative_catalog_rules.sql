ALTER TABLE public.menu_items
    ADD COLUMN pricing_mode varchar(32) NOT NULL DEFAULT 'BASE_PLUS_ADJUSTMENTS';

ALTER TABLE ONLY public.menu_items
    DROP CONSTRAINT menu_items_price_amount_positive_check,
    ADD CONSTRAINT menu_items_pricing_mode_check
        CHECK (pricing_mode IN ('BASE_PLUS_ADJUSTMENTS', 'SELECTION_SUM')),
    ADD CONSTRAINT menu_items_price_by_pricing_mode_check
        CHECK (
            (pricing_mode = 'BASE_PLUS_ADJUSTMENTS' AND price_amount > 0)
            OR
            (pricing_mode = 'SELECTION_SUM' AND price_amount = 0)
        );

ALTER TABLE public.menu_items
    ALTER COLUMN pricing_mode DROP DEFAULT;

-- This runs during Flyway, before the application acquires the rule-set row
-- lock. The verified empty-catalog bootstrap reserves IDs 1..121 explicitly,
-- while existing catalogs continue from their current maximum.
SELECT setval(
    pg_get_serial_sequence('public.menu_items', 'id'),
    COALESCE((SELECT max(id) FROM public.menu_items), 121),
    true
);

CREATE TABLE public.catalog_bootstrap_rule_sets (
    rule_set_id varchar(128) PRIMARY KEY,
    applied_at timestamp with time zone NULL,
    CONSTRAINT catalog_bootstrap_rule_sets_rule_set_id_not_blank_check
        CHECK (btrim(rule_set_id) <> '')
);

INSERT INTO public.catalog_bootstrap_rule_sets (rule_set_id, applied_at)
VALUES ('PHASE_6F1_AUTHORITATIVE_CATALOG_RULES', NULL);
