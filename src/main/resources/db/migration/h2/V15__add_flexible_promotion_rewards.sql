ALTER TABLE public.promotions DROP CONSTRAINT promotions_benefit_type_check;
ALTER TABLE public.promotions DROP CONSTRAINT promotions_benefit_parameters_check;
ALTER TABLE public.promotions ADD CONSTRAINT promotions_benefit_type_check
    CHECK (benefit_type IN ('FIXED_UNIT_PRICE', 'BUY_X_GET_Y_SAME_ITEM', 'BUY_X_GET_Y_ELIGIBLE_ITEM'));
ALTER TABLE public.promotions ADD CONSTRAINT promotions_benefit_parameters_check CHECK (
    (benefit_type = 'FIXED_UNIT_PRICE'
        AND fixed_unit_price_amount IS NOT NULL AND fixed_unit_price_amount > 0
        AND buy_quantity IS NULL AND reward_quantity IS NULL AND repeat_enabled IS NULL)
    OR (benefit_type IN ('BUY_X_GET_Y_SAME_ITEM', 'BUY_X_GET_Y_ELIGIBLE_ITEM')
        AND fixed_unit_price_amount IS NULL
        AND buy_quantity IS NOT NULL AND buy_quantity > 0
        AND reward_quantity IS NOT NULL AND reward_quantity > 0
        AND repeat_enabled IS NOT NULL)
);

ALTER TABLE public.order_lines DROP CONSTRAINT order_lines_promotion_benefit_type_check;
ALTER TABLE public.order_lines ADD CONSTRAINT order_lines_promotion_benefit_type_check
    CHECK (applied_promotion_benefit_type IS NULL
        OR applied_promotion_benefit_type IN ('FIXED_UNIT_PRICE', 'BUY_X_GET_Y_SAME_ITEM', 'BUY_X_GET_Y_ELIGIBLE_ITEM'));

ALTER TABLE public.order_line_selection_snapshots
    ADD COLUMN display_on_ticket boolean NOT NULL DEFAULT true;

UPDATE public.promotions
SET benefit_type = 'BUY_X_GET_Y_ELIGIBLE_ITEM',
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE name = 'Jueves 2x1'
  AND benefit_type = 'BUY_X_GET_Y_SAME_ITEM';
