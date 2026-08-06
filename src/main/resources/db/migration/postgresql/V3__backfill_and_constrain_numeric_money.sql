-- This setting applies only to the pre-function validation statements below.
-- PostgreSQL 16 documents values greater than zero as shortest-precise float output.
SET LOCAL extra_float_digits = 3;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.cart_items
        WHERE unit_price_amount IS NULL
          AND NOT (
              CASE
                  WHEN unit_price IS NULL
                    OR unit_price <= 0
                    OR unit_price = 'NaN'::double precision
                    OR unit_price = 'Infinity'::double precision
                    OR unit_price = '-Infinity'::double precision
                  THEN FALSE
                  ELSE unit_price::text::numeric = unit_price::text::numeric(19,2)
                   AND unit_price::text::numeric(19,2)::double precision = unit_price
              END
          )
    ) THEN
        RAISE EXCEPTION 'cart_items contains an incompatible legacy monetary value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.orders
        WHERE total_amount_amount IS NULL
          AND NOT (
              CASE
                  WHEN total_amount IS NULL
                    OR total_amount <= 0
                    OR total_amount = 'NaN'::double precision
                    OR total_amount = 'Infinity'::double precision
                    OR total_amount = '-Infinity'::double precision
                  THEN FALSE
                  ELSE total_amount::text::numeric = total_amount::text::numeric(19,2)
                   AND total_amount::text::numeric(19,2)::double precision = total_amount
              END
          )
    ) THEN
        RAISE EXCEPTION 'orders contains an incompatible legacy monetary value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.cart_items
        WHERE unit_price_amount IS NOT NULL
          AND (
              unit_price_amount <= 0
              OR unit_price_amount IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
              OR (
                  unit_price IS NOT NULL
                  AND NOT (
                      CASE
                          WHEN unit_price <= 0
                            OR unit_price = 'NaN'::double precision
                            OR unit_price = 'Infinity'::double precision
                            OR unit_price = '-Infinity'::double precision
                          THEN FALSE
                          ELSE unit_price::text::numeric = unit_price_amount
                           AND unit_price_amount::double precision = unit_price
                      END
                  )
              )
          )
    ) THEN
        RAISE EXCEPTION 'cart_items contains disagreeing monetary representations';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.orders
        WHERE total_amount_amount IS NOT NULL
          AND (
              total_amount_amount <= 0
              OR total_amount_amount IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
              OR (
                  total_amount IS NOT NULL
                  AND NOT (
                      CASE
                          WHEN total_amount <= 0
                            OR total_amount = 'NaN'::double precision
                            OR total_amount = 'Infinity'::double precision
                            OR total_amount = '-Infinity'::double precision
                          THEN FALSE
                          ELSE total_amount::text::numeric = total_amount_amount
                           AND total_amount_amount::double precision = total_amount
                      END
                  )
              )
          )
    ) THEN
        RAISE EXCEPTION 'orders contains disagreeing monetary representations';
    END IF;
END $$;

-- Flyway owns this PostgreSQL-only helper. It is schema-qualified at every call site.
-- Its fixed pg_catalog search path and extra_float_digits setting make float8 textual
-- conversion deterministic for CHECK constraints, independently of caller sessions.
-- Remove it only in a later migration that first removes both agreement constraints
-- and the legacy floating-point columns after the verified compatibility cutover.
CREATE FUNCTION public.checkout_money_java_double_to_numeric(value double precision)
RETURNS numeric
LANGUAGE SQL
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog
SET extra_float_digits = '3'
AS $$
    SELECT $1::text::numeric
$$;

COMMENT ON FUNCTION public.checkout_money_java_double_to_numeric(double precision)
IS 'Flyway V3 compatibility helper: shortest-precise float8 text with fixed extra_float_digits for Java BigDecimal.valueOf agreement checks.';

UPDATE public.cart_items
SET unit_price_amount = public.checkout_money_java_double_to_numeric(unit_price)::numeric(19,2)
WHERE unit_price_amount IS NULL;

UPDATE public.orders
SET total_amount_amount = public.checkout_money_java_double_to_numeric(total_amount)::numeric(19,2)
WHERE total_amount_amount IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.cart_items
        WHERE unit_price_amount IS NULL
           OR unit_price_amount <= 0
           OR unit_price_amount IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
           OR (
               unit_price IS NOT NULL
               AND NOT (
                   CASE
                       WHEN unit_price <= 0
                         OR unit_price = 'NaN'::double precision
                         OR unit_price = 'Infinity'::double precision
                         OR unit_price = '-Infinity'::double precision
                       THEN FALSE
                       ELSE public.checkout_money_java_double_to_numeric(unit_price) = unit_price_amount
                        AND unit_price_amount::double precision = unit_price
                   END
               )
           )
    ) THEN
        RAISE EXCEPTION 'cart_items monetary convergence validation failed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.orders
        WHERE total_amount_amount IS NULL
           OR total_amount_amount <= 0
           OR total_amount_amount IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
           OR (
               total_amount IS NOT NULL
               AND NOT (
                   CASE
                       WHEN total_amount <= 0
                         OR total_amount = 'NaN'::double precision
                         OR total_amount = 'Infinity'::double precision
                         OR total_amount = '-Infinity'::double precision
                       THEN FALSE
                       ELSE public.checkout_money_java_double_to_numeric(total_amount) = total_amount_amount
                        AND total_amount_amount::double precision = total_amount
                   END
               )
           )
    ) THEN
        RAISE EXCEPTION 'orders monetary convergence validation failed';
    END IF;
END $$;

ALTER TABLE public.cart_items
    ALTER COLUMN unit_price_amount SET NOT NULL;

ALTER TABLE public.orders
    ALTER COLUMN total_amount_amount SET NOT NULL;

ALTER TABLE public.cart_items
    ADD CONSTRAINT cart_items_unit_price_amount_positive_check
    CHECK (
        unit_price_amount > 0
        AND unit_price_amount NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
    );

ALTER TABLE public.cart_items
    ADD CONSTRAINT cart_items_money_representations_agree_check
    CHECK (
        unit_price IS NULL
        OR (
            CASE
                WHEN unit_price <= 0
                  OR unit_price = 'NaN'::double precision
                  OR unit_price = 'Infinity'::double precision
                  OR unit_price = '-Infinity'::double precision
                THEN FALSE
                ELSE public.checkout_money_java_double_to_numeric(unit_price) = unit_price_amount
                 AND unit_price_amount::double precision = unit_price
            END
        )
    );

ALTER TABLE public.orders
    ADD CONSTRAINT orders_total_amount_amount_positive_check
    CHECK (
        total_amount_amount > 0
        AND total_amount_amount NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
    );

ALTER TABLE public.orders
    ADD CONSTRAINT orders_money_representations_agree_check
    CHECK (
        total_amount IS NULL
        OR (
            CASE
                WHEN total_amount <= 0
                  OR total_amount = 'NaN'::double precision
                  OR total_amount = 'Infinity'::double precision
                  OR total_amount = '-Infinity'::double precision
                THEN FALSE
                ELSE public.checkout_money_java_double_to_numeric(total_amount) = total_amount_amount
                 AND total_amount_amount::double precision = total_amount
            END
        )
    );
