SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM cart_items
    WHERE unit_price_amount IS NULL
      AND NOT (
          CASE
              WHEN unit_price IS NULL
                OR unit_price <= 0
                OR unit_price = CAST('NaN' AS DOUBLE PRECISION)
                OR unit_price = CAST('Infinity' AS DOUBLE PRECISION)
                OR unit_price = CAST('-Infinity' AS DOUBLE PRECISION)
              THEN FALSE
              ELSE CAST(unit_price AS NUMERIC) = CAST(unit_price AS NUMERIC(19,2))
               AND CAST(CAST(unit_price AS NUMERIC(19,2)) AS DOUBLE PRECISION) = unit_price
          END
      )
) THEN SIGNAL('45000', 'cart_items contains an incompatible legacy monetary value') ELSE NULL END;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM orders
    WHERE total_amount_amount IS NULL
      AND NOT (
          CASE
              WHEN total_amount IS NULL
                OR total_amount <= 0
                OR total_amount = CAST('NaN' AS DOUBLE PRECISION)
                OR total_amount = CAST('Infinity' AS DOUBLE PRECISION)
                OR total_amount = CAST('-Infinity' AS DOUBLE PRECISION)
              THEN FALSE
              ELSE CAST(total_amount AS NUMERIC) = CAST(total_amount AS NUMERIC(19,2))
               AND CAST(CAST(total_amount AS NUMERIC(19,2)) AS DOUBLE PRECISION) = total_amount
          END
      )
) THEN SIGNAL('45000', 'orders contains an incompatible legacy monetary value') ELSE NULL END;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM cart_items
    WHERE unit_price_amount IS NOT NULL
      AND (
          unit_price_amount <= 0
          OR (
              unit_price IS NOT NULL
              AND NOT (
                  CASE
                      WHEN unit_price <= 0
                        OR unit_price = CAST('NaN' AS DOUBLE PRECISION)
                        OR unit_price = CAST('Infinity' AS DOUBLE PRECISION)
                        OR unit_price = CAST('-Infinity' AS DOUBLE PRECISION)
                      THEN FALSE
                      ELSE CAST(unit_price AS NUMERIC) = unit_price_amount
                       AND CAST(unit_price_amount AS DOUBLE PRECISION) = unit_price
                  END
              )
          )
      )
) THEN SIGNAL('45000', 'cart_items contains disagreeing monetary representations') ELSE NULL END;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM orders
    WHERE total_amount_amount IS NOT NULL
      AND (
          total_amount_amount <= 0
          OR (
              total_amount IS NOT NULL
              AND NOT (
                  CASE
                      WHEN total_amount <= 0
                        OR total_amount = CAST('NaN' AS DOUBLE PRECISION)
                        OR total_amount = CAST('Infinity' AS DOUBLE PRECISION)
                        OR total_amount = CAST('-Infinity' AS DOUBLE PRECISION)
                      THEN FALSE
                      ELSE CAST(total_amount AS NUMERIC) = total_amount_amount
                       AND CAST(total_amount_amount AS DOUBLE PRECISION) = total_amount
                  END
              )
          )
      )
) THEN SIGNAL('45000', 'orders contains disagreeing monetary representations') ELSE NULL END;

UPDATE cart_items
SET unit_price_amount = CAST(unit_price AS NUMERIC(19,2))
WHERE unit_price_amount IS NULL;

UPDATE orders
SET total_amount_amount = CAST(total_amount AS NUMERIC(19,2))
WHERE total_amount_amount IS NULL;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM cart_items
    WHERE unit_price_amount IS NULL
       OR unit_price_amount <= 0
       OR (
           unit_price IS NOT NULL
           AND NOT (
               CASE
                   WHEN unit_price <= 0
                     OR unit_price = CAST('NaN' AS DOUBLE PRECISION)
                     OR unit_price = CAST('Infinity' AS DOUBLE PRECISION)
                     OR unit_price = CAST('-Infinity' AS DOUBLE PRECISION)
                   THEN FALSE
                   ELSE CAST(unit_price AS NUMERIC) = unit_price_amount
                    AND CAST(unit_price_amount AS DOUBLE PRECISION) = unit_price
               END
           )
       )
) THEN SIGNAL('45000', 'cart_items monetary convergence validation failed') ELSE NULL END;

SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM orders
    WHERE total_amount_amount IS NULL
       OR total_amount_amount <= 0
       OR (
           total_amount IS NOT NULL
           AND NOT (
               CASE
                   WHEN total_amount <= 0
                     OR total_amount = CAST('NaN' AS DOUBLE PRECISION)
                     OR total_amount = CAST('Infinity' AS DOUBLE PRECISION)
                     OR total_amount = CAST('-Infinity' AS DOUBLE PRECISION)
                   THEN FALSE
                   ELSE CAST(total_amount AS NUMERIC) = total_amount_amount
                    AND CAST(total_amount_amount AS DOUBLE PRECISION) = total_amount
               END
           )
       )
) THEN SIGNAL('45000', 'orders monetary convergence validation failed') ELSE NULL END;

ALTER TABLE cart_items
    ALTER COLUMN unit_price_amount SET NOT NULL;

ALTER TABLE orders
    ALTER COLUMN total_amount_amount SET NOT NULL;

ALTER TABLE cart_items
    ADD CONSTRAINT cart_items_unit_price_amount_positive_check
    CHECK (unit_price_amount > 0);

ALTER TABLE cart_items
    ADD CONSTRAINT cart_items_money_representations_agree_check
    CHECK (
        unit_price IS NULL
        OR (
            CASE
                WHEN unit_price <= 0
                  OR unit_price = CAST('NaN' AS DOUBLE PRECISION)
                  OR unit_price = CAST('Infinity' AS DOUBLE PRECISION)
                  OR unit_price = CAST('-Infinity' AS DOUBLE PRECISION)
                THEN FALSE
                ELSE CAST(unit_price AS NUMERIC) = unit_price_amount
                 AND CAST(unit_price_amount AS DOUBLE PRECISION) = unit_price
            END
        )
    );

ALTER TABLE orders
    ADD CONSTRAINT orders_total_amount_amount_positive_check
    CHECK (total_amount_amount > 0);

ALTER TABLE orders
    ADD CONSTRAINT orders_money_representations_agree_check
    CHECK (
        total_amount IS NULL
        OR (
            CASE
                WHEN total_amount <= 0
                  OR total_amount = CAST('NaN' AS DOUBLE PRECISION)
                  OR total_amount = CAST('Infinity' AS DOUBLE PRECISION)
                  OR total_amount = CAST('-Infinity' AS DOUBLE PRECISION)
                THEN FALSE
                ELSE CAST(total_amount AS NUMERIC) = total_amount_amount
                 AND CAST(total_amount_amount AS DOUBLE PRECISION) = total_amount
            END
        )
    );
