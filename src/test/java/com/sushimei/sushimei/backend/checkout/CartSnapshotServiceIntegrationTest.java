package com.sushimei.sushimei.backend.checkout;

import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.repository.CartRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(CartSnapshotServiceIntegrationTest.TestInfrastructureConfiguration.class)
class CartSnapshotServiceIntegrationTest {

    @Autowired
    private CartSnapshotService cartSnapshotService;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void rejectsMissingActiveCartWithoutCreatingOne() {
        long cartCountBefore = cartRepository.count();

        assertThatThrownBy(() -> cartSnapshotService.readActiveCart("525512345678"))
                .isInstanceOf(ActiveCartNotFoundException.class);

        assertThat(cartRepository.count()).isEqualTo(cartCountBefore);
    }

    @Test
    void rejectsExactlyOneEmptyActiveCart() {
        persistCart("525512345678");

        assertThatThrownBy(() -> cartSnapshotService.readActiveCart("525512345678"))
                .isInstanceOf(EmptyCartException.class);
    }

    @Test
    void readsOneValidActiveCartWithExactLineAndCartTotals() {
        Cart cart = persistCart("525512345678", item("Maki", 2, 10.5d), item("Te", 1, 0.25d));

        CartSnapshot snapshot = cartSnapshotService.readActiveCart(" 525512345678 ");

        assertThat(snapshot.cartId()).isEqualTo(cart.getId());
        assertThat(snapshot.items()).hasSize(2);
        assertThat(snapshot.items().stream().map(CartLineSnapshot::lineTotal).toList())
                .containsExactly(new BigDecimal("21.00"), new BigDecimal("0.25"));
        assertThat(snapshot.total()).isEqualByComparingTo("21.25").hasScaleOf(2);
    }

    @Test
    void rejectsMultipleOpenCartsInsteadOfSelectingOne() {
        persistCart("525512345678", item("Maki", 1, 10.0d));
        persistCart("525512345678", item("Rollo", 1, 20.0d));

        assertThatThrownBy(() -> cartSnapshotService.readActiveCart("525512345678"))
                .isInstanceOf(MultipleActiveCartsException.class);
    }

    @Test
    void ordersSnapshotLinesByPersistedCartItemId() {
        Cart cart = persistCart("525512345678", item("First", 1, 10.0d), item("Second", 1, 20.0d));
        List<Long> persistedItemIds = cart.getItems().stream()
                .map(CartItem::getId)
                .sorted()
                .toList();

        CartSnapshot snapshot = cartSnapshotService.readActiveCart("525512345678");

        assertThat(snapshot.items().stream().map(CartLineSnapshot::cartItemId).toList())
                .containsExactlyElementsOf(persistedItemIds);
    }

    @Test
    void snapshotIsImmutableAndDoesNotChangeWhenManagedEntitiesChange() {
        Cart cart = persistCart("525512345678", item("Maki", 1, 10.0d));
        CartSnapshot snapshot = cartSnapshotService.readActiveCart("525512345678");
        CartLineSnapshot originalLine = snapshot.items().get(0);

        assertThatThrownBy(() -> snapshot.items().add(originalLine))
                .isInstanceOf(UnsupportedOperationException.class);
        cart.getItems().get(0).setDishName("Changed");
        cart.getItems().get(0).setQuantity(9);
        cart.getItems().get(0).setUnitPrice(99.0d);

        assertThat(snapshot.items()).containsExactly(originalLine);
        assertThat(snapshot.total()).isEqualByComparingTo("10.00");
    }

    @Test
    void readDoesNotChangeCartStatusOrCreateOrDeleteCarts() {
        Cart cart = persistCart("525512345678", item("Maki", 1, 10.0d));
        long cartCountBefore = cartRepository.count();

        cartSnapshotService.readActiveCart("525512345678");
        entityManager.flush();
        entityManager.clear();

        Cart reloaded = cartRepository.findById(cart.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("OPEN");
        assertThat(cartRepository.count()).isEqualTo(cartCountBefore);
    }

    @Test
    void rejectsNullPersistedPrice() {
        persistCart("525512345678", item("Maki", 1, null));

        assertInvalidUnitPrice(InvalidCartItemReason.MISSING_MONETARY_REPRESENTATIONS);
    }

    @Test
    void rejectsNaNPersistedPriceWhenH2StoresIt() {
        persistCart("525512345678", item("Maki", 1, Double.NaN));

        assertInvalidUnitPrice(InvalidCartItemReason.INVALID_LEGACY_UNIT_PRICE);
    }

    @Test
    void rejectsInfinitePersistedPriceWhenH2StoresIt() {
        persistCart("525512345678", item("Maki", 1, Double.POSITIVE_INFINITY));

        assertInvalidUnitPrice(InvalidCartItemReason.INVALID_LEGACY_UNIT_PRICE);
    }

    @Test
    void rejectsZeroNegativeAndExcessiveScalePrices() {
        persistCart("zero", item("Maki", 1, 0.0d));
        persistCart("negative", item("Maki", 1, -10.0d));
        persistCart("scale", item("Maki", 1, 10.001d));

        assertInvalidUnitPrice("zero", InvalidCartItemReason.INVALID_LEGACY_UNIT_PRICE);
        assertInvalidUnitPrice("negative", InvalidCartItemReason.INVALID_LEGACY_UNIT_PRICE);
        assertInvalidUnitPrice("scale", InvalidCartItemReason.INVALID_LEGACY_UNIT_PRICE);
    }

    @Test
    void rejectsAnInvalidQuantity() {
        persistCart("525512345678", item("Maki", 0, 10.0d));

        assertThatThrownBy(() -> cartSnapshotService.readActiveCart("525512345678"))
                .isInstanceOf(InvalidCartItemException.class)
                .extracting(exception -> ((InvalidCartItemException) exception).getReason())
                .isEqualTo(InvalidCartItemReason.INVALID_QUANTITY);
    }

    private void assertInvalidUnitPrice(InvalidCartItemReason reason) {
        assertInvalidUnitPrice("525512345678", reason);
    }

    private void assertInvalidUnitPrice(String phoneNumber, InvalidCartItemReason reason) {
        assertThatThrownBy(() -> cartSnapshotService.readActiveCart(phoneNumber))
                .isInstanceOf(InvalidCartItemException.class)
                .extracting(exception -> ((InvalidCartItemException) exception).getReason())
                .isEqualTo(reason);
    }

    private Cart persistCart(String phoneNumber, CartItem... items) {
        Cart cart = new Cart();
        cart.setPhoneNumber(phoneNumber);
        cart.setStatus("OPEN");
        for (CartItem item : items) {
            cart.addItem(item);
        }
        Cart saved = cartRepository.saveAndFlush(cart);
        entityManager.clear();
        return cartRepository.findById(saved.getId()).orElseThrow();
    }

    private CartItem item(String dishName, int quantity, Double unitPrice) {
        CartItem item = new CartItem();
        item.setDishName(dishName);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        return item;
    }

    @Test
    void readsNumericOnlyHistoricalData() {
        CartItem item = item("Maki", 2, null);
        item.setUnitPriceAmount(new BigDecimal("10.50"));
        persistCart("numeric-only", item);

        CartSnapshot snapshot = cartSnapshotService.readActiveCart("numeric-only");

        assertThat(snapshot.total()).isEqualByComparingTo("21.00");
    }

    @Test
    void rejectsMismatchingDualRepresentationsExplicitly() {
        CartItem item = item("Maki", 1, 10.5d);
        item.setUnitPriceAmount(new BigDecimal("10.51"));
        persistCart("mismatch", item);

        assertInvalidUnitPrice("mismatch", InvalidCartItemReason.UNIT_PRICE_REPRESENTATIONS_DISAGREE);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        @Bean
        ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
