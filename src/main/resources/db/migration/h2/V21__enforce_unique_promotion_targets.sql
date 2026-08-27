-- Preserve the oldest identical target row before enforcing the logical target invariant.
DELETE FROM public.promotion_targets AS duplicate
WHERE EXISTS (
    SELECT 1
    FROM public.promotion_targets AS canonical
    WHERE canonical.promotion_id = duplicate.promotion_id
      AND canonical.id < duplicate.id
      AND (
          canonical.target_menu_item_id = duplicate.target_menu_item_id
          OR canonical.target_tag_id = duplicate.target_tag_id
      )
);

ALTER TABLE public.promotion_targets
    ADD CONSTRAINT promotion_targets_promotion_menu_item_key
        UNIQUE (promotion_id, target_menu_item_id);

ALTER TABLE public.promotion_targets
    ADD CONSTRAINT promotion_targets_promotion_tag_key
        UNIQUE (promotion_id, target_tag_id);
