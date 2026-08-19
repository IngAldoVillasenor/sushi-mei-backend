ALTER TABLE orders ADD COLUMN external_order_id VARCHAR(120);
ALTER TABLE orders ADD COLUMN external_reference VARCHAR(120);
CREATE UNIQUE INDEX idx_orders_source_ext_id ON orders (order_source, external_order_id) WHERE external_order_id IS NOT NULL;

ALTER TABLE order_lines ADD COLUMN external_product_reference VARCHAR(120);

ALTER TABLE orders DROP CONSTRAINT orders_order_source_check;
ALTER TABLE orders ADD CONSTRAINT orders_order_source_check CHECK (order_source IS NULL OR order_source IN ('WHATSAPP_AI', 'ANDROID_MANUAL', 'COUNTER', 'VENDIS_IMPORT'));


ALTER TABLE order_lines DROP CONSTRAINT order_lines_provenance_check;
ALTER TABLE order_lines ADD CONSTRAINT order_lines_provenance_check
    CHECK (
        (line_kind = 'PAID' AND (source_cart_item_id IS NOT NULL OR source_menu_item_id IS NOT NULL OR (external_product_reference IS NOT NULL AND btrim(external_product_reference) <> '')))
        OR
        (line_kind = 'PROMOTION_REWARD' AND applied_promotion_id IS NOT NULL AND applied_promotion_benefit_type IS NOT NULL)
    );
