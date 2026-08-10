package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.CatalogTag;
import com.sushimei.sushimei.backend.catalog.MenuItem;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "promotion_targets")
public class PromotionTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_menu_item_id")
    private MenuItem targetMenuItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_tag_id")
    private CatalogTag targetTag;

    protected PromotionTarget() {
        // JPA
    }

    static PromotionTarget create(Promotion promotion, MenuItem targetMenuItem, CatalogTag targetTag) {
        PromotionTarget target = new PromotionTarget();
        target.promotion = promotion;
        target.targetMenuItem = targetMenuItem;
        target.targetTag = targetTag;
        return target;
    }

    public Long getId() { return id; }
    public Promotion getPromotion() { return promotion; }
    public MenuItem getTargetMenuItem() { return targetMenuItem; }
    public CatalogTag getTargetTag() { return targetTag; }
}
