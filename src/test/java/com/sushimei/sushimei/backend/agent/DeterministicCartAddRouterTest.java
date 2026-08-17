package com.sushimei.sushimei.backend.agent;

import com.sushimei.sushimei.backend.catalog.MenuCatalogRepository;
import com.sushimei.sushimei.backend.catalog.MenuItem;
import com.sushimei.sushimei.backend.catalog.MenuItemPricingMode;
import com.sushimei.sushimei.backend.tools.OrderTools;
import com.sushimei.sushimei.backend.tools.ResolvedMenuItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeterministicCartAddRouterTest {

    private static final String PHONE_NUMBER = "5214770000001";

    @Mock
    private MenuCatalogRepository menuCatalogRepository;

    @Mock
    private OrderTools orderTools;

    private DeterministicCartAddRouter router;

    @BeforeEach
    void setUp() {
        router = new DeterministicCartAddRouter(menuCatalogRepository, orderTools);
        lenient().when(orderTools.addServerResolvedDishToCart(anyString(), any(ResolvedMenuItem.class), anyInt()))
                .thenReturn("ok");
    }

    @Test
    void screenshotRequestAddsEveryExplicitProductWithItsBoundQuantity() {
        catalog(chorizo(), mangoRoll(), calpiMango(), calpiFresa(), coca600(), coca175());

        assertThat(router.tryAdd(PHONE_NUMBER,
                "Hola quisiera ordenar un chorizo roll 1 mango roll y 2 calpis de mango por favor"))
                .isPresent();

        ArgumentCaptor<ResolvedMenuItem> item = ArgumentCaptor.forClass(ResolvedMenuItem.class);
        ArgumentCaptor<Integer> quantity = ArgumentCaptor.forClass(Integer.class);
        verify(orderTools, org.mockito.Mockito.times(3))
                .addServerResolvedDishToCart(org.mockito.ArgumentMatchers.eq(PHONE_NUMBER), item.capture(), quantity.capture());
        assertThat(item.getAllValues()).extracting(ResolvedMenuItem::name)
                .containsExactly("Chorizo roll", "Mango roll", "Calpi de mango (Bebida Japonesa)");
        assertThat(quantity.getAllValues()).containsExactly(1, 1, 2);
    }

    @Test
    void quantityOnlyFollowUpsAndPluralNamesAreResolvedWithoutTheModel() {
        catalog(calpiMango(), calpiFresa(), coca600(), coca175());

        assertThat(router.tryAdd(PHONE_NUMBER, "2 Calpi de Mango")).isPresent();
        assertThat(router.tryAdd(PHONE_NUMBER, "Que sean 2 Calpis de Fresa entonces")).isPresent();
        assertThat(router.tryAdd(PHONE_NUMBER, "Me agregas 2 cocas de 600 ml por favor")).isPresent();

        ArgumentCaptor<ResolvedMenuItem> item = ArgumentCaptor.forClass(ResolvedMenuItem.class);
        verify(orderTools, org.mockito.Mockito.times(3))
                .addServerResolvedDishToCart(org.mockito.ArgumentMatchers.eq(PHONE_NUMBER), item.capture(),
                        org.mockito.ArgumentMatchers.eq(2));
        assertThat(item.getAllValues()).extracting(ResolvedMenuItem::name)
                .containsExactly("Calpi de mango (Bebida Japonesa)",
                        "Calpi de Fresa 500ml (Bebida Japonesa)", "Coca 600 ml (Refresco)");
    }

    @Test
    void ambiguousOrConflictingPresentationsRemainUnchanged() {
        catalog(calpiMango(), coca600(), coca175());

        assertThat(router.tryAdd(PHONE_NUMBER, "Agrega una Coca")).isEmpty();
        assertThat(router.tryAdd(PHONE_NUMBER, "Calpi de Mango de 500 ml")).isEmpty();

        verify(orderTools, never()).addServerResolvedDishToCart(anyString(), any(ResolvedMenuItem.class), anyInt());
    }

    @Test
    void mixedAddAndRemoveMessageStaysWithTheConversationalGuard() {
        assertThat(router.tryAdd(PHONE_NUMBER, "Quita la Coca y agrega 2 Calpis de Mango")).isEmpty();

        verify(orderTools, never()).addServerResolvedDishToCart(anyString(), any(ResolvedMenuItem.class), anyInt());
    }

    private void catalog(MenuItem... items) {
        when(menuCatalogRepository.findByActiveTrueAndStandaloneOrderableTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc())
                .thenReturn(List.of(items));
    }

    private MenuItem chorizo() {
        return item("Chorizo roll", "89.00");
    }

    private MenuItem mangoRoll() {
        return item("Mango roll", "109.00");
    }

    private MenuItem calpiMango() {
        return item("Calpi de mango (Bebida Japonesa)", "35.00");
    }

    private MenuItem calpiFresa() {
        return item("Calpi de Fresa 500ml (Bebida Japonesa)", "35.00");
    }

    private MenuItem coca600() {
        return item("Coca 600 ml (Refresco)", "28.00");
    }

    private MenuItem coca175() {
        return item("Coca 1.75 ml (Refresco)", "45.00");
    }

    private MenuItem item(String name, String price) {
        MenuItem item = org.mockito.Mockito.mock(MenuItem.class);
        when(item.getName()).thenReturn(name);
        when(item.getPriceAmount()).thenReturn(new BigDecimal(price));
        when(item.getPricingMode()).thenReturn(MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS);
        when(item.isAvailable()).thenReturn(true);
        return item;
    }
}
