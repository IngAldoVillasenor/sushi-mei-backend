ALTER TABLE public.cart_items
    ADD COLUMN unit_price_amount NUMERIC(19,2);

ALTER TABLE public.orders
    ADD COLUMN total_amount_amount NUMERIC(19,2);
