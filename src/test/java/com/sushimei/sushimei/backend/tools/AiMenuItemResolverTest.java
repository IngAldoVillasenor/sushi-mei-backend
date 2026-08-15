package com.sushimei.sushimei.backend.tools;

import com.sushimei.sushimei.backend.catalog.MenuCatalogRepository;
import com.sushimei.sushimei.backend.catalog.MenuItem;
import com.sushimei.sushimei.backend.catalog.MenuItemPricingMode;
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
    private final AiMenuItemResolver resolver = new AiMenuItemResolver(menuCatalogRepository);

    @Test
    void resolvesCanonicalNameAndPriceFromTheOperationalCatalog() {
        MenuItem item = mock(MenuItem.class);
        when(item.getId()).thenReturn(47L);
        when(item.getName()).thenReturn("Empanizado ebi");
        when(item.getPriceAmount()).thenReturn(new BigDecimal("99.00"));
        when(item.getPricingMode()).thenReturn(MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS);
        when(menuCatalogRepository
                .findByNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
                        "empanizado ebi"))
                .thenReturn(List.of(item));

        ResolvedMenuItem resolved = resolver.resolveExact(" empanizado ebi ");

        assertThat(resolved.name()).isEqualTo("Empanizado ebi");
        assertThat(resolved.unitPrice()).isEqualByComparingTo("99.00");
        verify(menuCatalogRepository)
                .findByNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
                        "empanizado ebi");
    }

    @Test
    void rejectsNamesThatDoNotResolveToOneOrderableCatalogItem() {
        when(menuCatalogRepository
                .findByNameIgnoreCaseAndActiveTrueAndAvailableTrueAndStandaloneOrderableTrueOrderByIdAsc(
                        "Producto inventado"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveExact("Producto inventado"))
                .isInstanceOf(AiMenuItemResolutionException.class);
    }
}
