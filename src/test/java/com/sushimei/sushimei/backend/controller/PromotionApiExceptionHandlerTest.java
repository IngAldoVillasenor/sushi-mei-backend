package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.catalog.CatalogConfigurationException;
import com.sushimei.sushimei.backend.catalog.CatalogDomainError;
import com.sushimei.sushimei.backend.promotion.Promotion;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionApiExceptionHandlerTest {

    private final PromotionApiExceptionHandler handler = new PromotionApiExceptionHandler();

    @Test
    void mapsRealOptimisticLockFailuresToPromotionVersionConflict() {
        ResponseEntity<PromotionApiError> response = handler.handleOptimisticLockFailure(
                new ObjectOptimisticLockingFailureException(Promotion.class, 1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).extracting(PromotionApiError::code).isEqualTo("PROMOTION_VERSION_CONFLICT");
    }

    @Test
    void preservesCatalogUnavailableAndIncompleteStatusSemantics() {
        ResponseEntity<PromotionApiError> unavailable = handler.handleCatalogConfiguration(
                new CatalogConfigurationException(CatalogDomainError.MENU_ITEM_UNAVAILABLE));
        ResponseEntity<PromotionApiError> incomplete = handler.handleCatalogConfiguration(
                new CatalogConfigurationException(CatalogDomainError.MENU_CONFIGURATION_INCOMPLETE));

        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unavailable.getBody()).extracting(PromotionApiError::code).isEqualTo("MENU_ITEM_UNAVAILABLE");
        assertThat(incomplete.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(incomplete.getBody()).extracting(PromotionApiError::code).isEqualTo("MENU_CONFIGURATION_INCOMPLETE");
    }
}