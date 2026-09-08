package com.cardovia.merkon.backend.promotion;

import com.cardovia.merkon.backend.catalog.CatalogTag;
import com.cardovia.merkon.backend.catalog.MenuItem;

record PromotionTargetDraft(MenuItem targetMenuItem, CatalogTag targetTag) {
}
