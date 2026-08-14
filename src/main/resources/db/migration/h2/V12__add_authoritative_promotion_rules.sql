CREATE TABLE public.promotion_bootstrap_rule_sets (
    rule_set_id varchar(128) PRIMARY KEY,
    applied_at timestamp with time zone NULL,
    CONSTRAINT promotion_bootstrap_rule_sets_rule_set_id_not_blank_check
        CHECK (btrim(rule_set_id) <> '')
);

INSERT INTO public.promotion_bootstrap_rule_sets (rule_set_id, applied_at)
VALUES ('PHASE_6G_P0_A_AUTHORITATIVE_TEMPORAL_PROMOTIONS', NULL);
