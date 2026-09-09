-- V28 follows V27 directly. The legacy tenant is identified by a stable
-- migration-owned key, never by a runtime "first business" lookup.
ALTER TABLE businesses ADD COLUMN legacy_key varchar(64);
CREATE UNIQUE INDEX businesses_legacy_key_unique_idx ON businesses (legacy_key);
UPDATE businesses SET legacy_key = 'SUSHIMEI_LEGACY' WHERE name = 'Sushi Mei';

ALTER TABLE menu_items ADD COLUMN business_id bigint;
ALTER TABLE catalog_tags ADD COLUMN business_id bigint;
ALTER TABLE promotions ADD COLUMN business_id bigint;
ALTER TABLE orders ADD COLUMN business_id bigint;
ALTER TABLE business_days ADD COLUMN business_id bigint;
ALTER TABLE cart ADD COLUMN business_id bigint;
ALTER TABLE conversation_sessions ADD COLUMN business_id bigint;
ALTER TABLE whatsapp_inbound_messages ADD COLUMN business_id bigint;
ALTER TABLE business_day_cash_expenses ADD COLUMN business_id bigint;

UPDATE menu_items SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE catalog_tags SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE promotions SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE orders SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE business_days SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE cart SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE conversation_sessions SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE whatsapp_inbound_messages SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE business_day_cash_expenses expense SET business_id = (
    SELECT business_day.business_id FROM business_days business_day WHERE business_day.id = expense.business_day_id);

ALTER TABLE menu_items ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE catalog_tags ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE promotions ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE orders ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE business_days ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE cart ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE conversation_sessions ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE whatsapp_inbound_messages ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE business_day_cash_expenses ALTER COLUMN business_id SET NOT NULL;

ALTER TABLE menu_items ADD CONSTRAINT menu_items_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);
ALTER TABLE catalog_tags ADD CONSTRAINT catalog_tags_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);
ALTER TABLE promotions ADD CONSTRAINT promotions_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);
ALTER TABLE orders ADD CONSTRAINT orders_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);
ALTER TABLE business_days ADD CONSTRAINT business_days_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);
ALTER TABLE cart ADD CONSTRAINT cart_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);
ALTER TABLE conversation_sessions ADD CONSTRAINT conversation_sessions_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);
ALTER TABLE whatsapp_inbound_messages ADD CONSTRAINT whatsapp_inbound_messages_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);
ALTER TABLE business_day_cash_expenses ADD CONSTRAINT business_day_cash_expenses_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses(id);

ALTER TABLE catalog_tags DROP CONSTRAINT catalog_tags_code_key;
ALTER TABLE catalog_tags ADD CONSTRAINT catalog_tags_business_code_key UNIQUE (business_id, code);
ALTER TABLE orders DROP CONSTRAINT orders_client_request_id_key;
ALTER TABLE orders ADD CONSTRAINT orders_business_client_request_id_key UNIQUE (business_id, client_request_id);
ALTER TABLE business_day_cash_expenses DROP CONSTRAINT business_day_cash_expenses_client_request_id_key;
ALTER TABLE business_day_cash_expenses ADD CONSTRAINT business_day_cash_expenses_business_client_request_id_key UNIQUE (business_id, client_request_id);
DROP INDEX idx_orders_source_ext_id;
CREATE UNIQUE INDEX idx_orders_business_source_ext_id ON orders (business_id, order_source, external_order_id);

ALTER TABLE business_days DROP CONSTRAINT business_days_business_date_key;
ALTER TABLE business_days DROP CONSTRAINT business_days_open_guard_key;
ALTER TABLE business_days ADD CONSTRAINT business_days_business_date_key UNIQUE (business_id, business_date);
ALTER TABLE business_days ADD CONSTRAINT business_days_business_open_guard_key UNIQUE (business_id, open_guard);

ALTER TABLE business_day_operation_locks DROP CONSTRAINT business_day_operation_locks_key_check;
ALTER TABLE business_day_operation_locks ALTER COLUMN lock_key RENAME TO business_id;
ALTER TABLE business_day_operation_locks ALTER COLUMN business_id BIGINT;
UPDATE business_day_operation_locks SET business_id = (SELECT id FROM businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
ALTER TABLE business_day_operation_locks
    ADD CONSTRAINT business_day_operation_locks_business_id_fkey
    FOREIGN KEY (business_id) REFERENCES businesses(id);

CREATE INDEX menu_items_business_active_category_display_order_name_id_idx
    ON menu_items (business_id, active, category, display_order, name, id);
CREATE INDEX catalog_tags_business_active_display_order_code_id_idx
    ON catalog_tags (business_id, active, display_order, code, id);
CREATE INDEX promotions_business_active_priority_id_idx ON promotions (business_id, active, priority DESC, id);
CREATE INDEX orders_business_status_created_id_idx ON orders (business_id, status, created_at, id);
CREATE INDEX orders_business_created_id_idx ON orders (business_id, created_at, id);
CREATE INDEX business_days_business_status_date_idx ON business_days (business_id, status, business_date);
CREATE INDEX business_day_cash_expenses_business_created_id_idx ON business_day_cash_expenses (business_id, created_at, id);
CREATE INDEX cart_business_phone_status_idx ON cart (business_id, phone_number, status);
