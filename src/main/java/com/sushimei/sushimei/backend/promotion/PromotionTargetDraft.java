package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.CatalogTag;
import com.sushimei.sushimei.backend.catalog.MenuItem;

record PromotionTargetDraft(MenuItem targetMenuItem, CatalogTag targetTag) {
}
