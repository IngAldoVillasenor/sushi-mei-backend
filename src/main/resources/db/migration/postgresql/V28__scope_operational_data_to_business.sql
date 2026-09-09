-- V28 follows V27 directly. The legacy tenant is identified by a stable
-- migration-owned key, never by a runtime "first business" lookup.
ALTER TABLE public.businesses ADD COLUMN legacy_key varchar(64);
CREATE UNIQUE INDEX businesses_legacy_key_unique_idx
    ON public.businesses (legacy_key) WHERE legacy_key IS NOT NULL;
UPDATE public.businesses SET legacy_key = 'SUSHIMEI_LEGACY' WHERE name = 'Sushi Mei';

-- A zero or ambiguous legacy name fails this migration: zero leaves the
-- mandatory backfills null; more than one violates the unique legacy key.
ALTER TABLE public.menu_items ADD COLUMN business_id bigint;
ALTER TABLE public.catalog_tags ADD COLUMN business_id bigint;
ALTER TABLE public.promotions ADD COLUMN business_id bigint;
ALTER TABLE public.orders ADD COLUMN business_id bigint;
ALTER TABLE public.business_days ADD COLUMN business_id bigint;
ALTER TABLE public.cart ADD COLUMN business_id bigint;
ALTER TABLE public.conversation_sessions ADD COLUMN business_id bigint;
ALTER TABLE public.whatsapp_inbound_messages ADD COLUMN business_id bigint;
ALTER TABLE public.business_day_cash_expenses ADD COLUMN business_id bigint;

UPDATE public.menu_items SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE public.catalog_tags SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE public.promotions SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE public.orders SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE public.business_days SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE public.cart SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE public.conversation_sessions SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE public.whatsapp_inbound_messages SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
UPDATE public.business_day_cash_expenses expense SET business_id = (
    SELECT business_day.business_id FROM public.business_days business_day WHERE business_day.id = expense.business_day_id);

ALTER TABLE public.menu_items ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE public.catalog_tags ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE public.promotions ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE public.orders ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE public.business_days ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE public.cart ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE public.conversation_sessions ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE public.whatsapp_inbound_messages ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE public.business_day_cash_expenses ALTER COLUMN business_id SET NOT NULL;

ALTER TABLE public.menu_items ADD CONSTRAINT menu_items_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);
ALTER TABLE public.catalog_tags ADD CONSTRAINT catalog_tags_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);
ALTER TABLE public.promotions ADD CONSTRAINT promotions_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);
ALTER TABLE public.orders ADD CONSTRAINT orders_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);
ALTER TABLE public.business_days ADD CONSTRAINT business_days_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);
ALTER TABLE public.cart ADD CONSTRAINT cart_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);
ALTER TABLE public.conversation_sessions ADD CONSTRAINT conversation_sessions_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);
ALTER TABLE public.whatsapp_inbound_messages ADD CONSTRAINT whatsapp_inbound_messages_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);
ALTER TABLE public.business_day_cash_expenses ADD CONSTRAINT business_day_cash_expenses_business_id_fkey FOREIGN KEY (business_id) REFERENCES public.businesses(id);

ALTER TABLE public.catalog_tags DROP CONSTRAINT catalog_tags_code_key;
ALTER TABLE public.catalog_tags ADD CONSTRAINT catalog_tags_business_code_key UNIQUE (business_id, code);
ALTER TABLE public.orders DROP CONSTRAINT orders_client_request_id_key;
ALTER TABLE public.orders ADD CONSTRAINT orders_business_client_request_id_key UNIQUE (business_id, client_request_id);
ALTER TABLE public.business_day_cash_expenses DROP CONSTRAINT business_day_cash_expenses_client_request_id_key;
ALTER TABLE public.business_day_cash_expenses ADD CONSTRAINT business_day_cash_expenses_business_client_request_id_key UNIQUE (business_id, client_request_id);
DROP INDEX public.idx_orders_source_ext_id;
CREATE UNIQUE INDEX idx_orders_business_source_ext_id
    ON public.orders (business_id, order_source, external_order_id) WHERE external_order_id IS NOT NULL;

ALTER TABLE public.business_days DROP CONSTRAINT business_days_business_date_key;
ALTER TABLE public.business_days DROP CONSTRAINT business_days_open_guard_key;
ALTER TABLE public.business_days ADD CONSTRAINT business_days_business_date_key UNIQUE (business_id, business_date);
ALTER TABLE public.business_days ADD CONSTRAINT business_days_business_open_guard_key UNIQUE (business_id, open_guard);

ALTER TABLE public.business_day_operation_locks DROP CONSTRAINT business_day_operation_locks_key_check;
ALTER TABLE public.business_day_operation_locks RENAME COLUMN lock_key TO business_id;
ALTER TABLE public.business_day_operation_locks ALTER COLUMN business_id TYPE bigint;
UPDATE public.business_day_operation_locks
SET business_id = (SELECT id FROM public.businesses WHERE legacy_key = 'SUSHIMEI_LEGACY');
ALTER TABLE public.business_day_operation_locks
    ADD CONSTRAINT business_day_operation_locks_business_id_fkey
    FOREIGN KEY (business_id) REFERENCES public.businesses(id);

CREATE INDEX menu_items_business_active_category_display_order_name_id_idx
    ON public.menu_items (business_id, active, category, display_order, name, id);
CREATE INDEX catalog_tags_business_active_display_order_code_id_idx
    ON public.catalog_tags (business_id, active, display_order, code, id);
CREATE INDEX promotions_business_active_priority_id_idx
    ON public.promotions (business_id, active, priority DESC, id);
CREATE INDEX orders_business_status_created_id_idx
    ON public.orders (business_id, status, created_at, id);
CREATE INDEX orders_business_created_id_idx ON public.orders (business_id, created_at, id);
CREATE INDEX business_days_business_status_date_idx ON public.business_days (business_id, status, business_date);
CREATE INDEX business_day_cash_expenses_business_created_id_idx ON public.business_day_cash_expenses (business_id, created_at, id);
CREATE INDEX cart_business_phone_status_idx ON public.cart (business_id, phone_number, status);
