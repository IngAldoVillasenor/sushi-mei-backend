package com.cardovia.merkon.backend.tools;

import com.cardovia.merkon.backend.catalog.MenuCatalogRepository;
import com.cardovia.merkon.backend.catalog.MenuItem;
import com.cardovia.merkon.backend.catalog.MenuItemPricingMode;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMenuItemResolverTest {

    private final MenuCatalogRepository menuCatalogRepository = mock(MenuCatalogRepository.class);
    private final LegacyBusinessResolver legacyBusinessResolver = mock(LegacyBusinessResolver.class);
    private final AiMenuItemResolver resolver = new AiMenuItemResolver(menuCatalogRepository, legacyBusinessResolver);

    @Test
    void resolvesCanonicalNameAndPriceFromTheOperationalCatalog() {
        MenuItem item = mock(MenuItem.class);
        when(item.getId()).thenReturn(47L);
        when(item.getName()).thenReturn("Empanizado ebi");
        when(item.getPriceAmount()).thenReturn(new BigDecimal("99.00"));
        when(item.getPricingMode()).thenReturn(MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS);
        when(menuCatalogRepository
                .findByBusinessIdAndNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
                        3L, "empanizado ebi"))
                .thenReturn(List.of(item));
        when(legacyBusinessResolver.requireLegacyBusinessId()).thenReturn(3L);

        ResolvedMenuItem resolved = resolver.resolveExact(" empanizado ebi ");

        assertThat(resolved.name()).isEqualTo("Empanizado ebi");
        assertThat(resolved.unitPrice()).isEqualByComparingTo("99.00");
        verify(menuCatalogRepository)
                .findByBusinessIdAndNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
                        3L, "empanizado ebi");
    }

    @Test
    void rejectsNamesThatDoNotResolveToOneOrderableCatalogItem() {
        when(menuCatalogRepository
                .findByBusinessIdAndNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
                        3L, "Producto inventado"))
                .thenReturn(List.of());
        when(legacyBusinessResolver.requireLegacyBusinessId()).thenReturn(3L);

        assertThatThrownBy(() -> resolver.resolveExact("Producto inventado"))
                .isInstanceOf(AiMenuItemResolutionException.class);
    }
}
