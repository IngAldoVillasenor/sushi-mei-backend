package com.sushimei.sushimei.backend.service;

import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.checkout.MonetaryCompatibilityException;
import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.CartRepository;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.tools.OrderTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, CartServiceDualMoneyIntegrationTest.TestInfrastructureConfiguration.class})
class CartServiceDualMoneyIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderTools orderTools;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void cleanDatabase() {
        orderRepository.deleteAll();
        cartRepository.deleteAll();
    }

    @Test
    void addItemPersistsBothMoneyColumns() {
        cartService.addItem("525512345678", "Maki", 2, 10.5d);
        entityManager.clear();

        Cart cart = cartWithItems(cartRepository.findByPhoneNumberAndStatus("525512345678", "OPEN").getId());
        CartItem item = cart.getItems().get(0);
        assertThat(item.getUnitPrice()).isEqualTo(10.5d);
        assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("10.50");
    }

    @Test
    void invalidAddItemCreatesNeitherCartNorCartItem() {
        assertThatThrownBy(() -> cartService.addItem("525512345678", "Maki", 1, Double.NaN))
                .isInstanceOf(MonetaryCompatibilityException.class);
        entityManager.clear();

        assertThat(cartRepository.count()).isZero();
        assertThat(countCartItems()).isZero();
    }

    @Test
    void confirmOrderPersistsBothTotalColumns() {
        cartService.addItem("525512345678", "Maki", 2, 10.5d);

        orderTools.confirmOrder("525512345678", "DOMICILIO", "Calle Cinco", "Efectivo 500");
        entityManager.clear();

        OrderRecord order = orderRepository.findAll().get(0);
        assertThat(order.getTotalAmount()).isEqualTo(21.0d);
        assertThat(order.getTotalAmountAmount()).isEqualByComparingTo("21.00");
    }

    @Test
    void unsafeTotalCreatesNoOrderAndLeavesCartOpen() {
        Cart cart = persistCart("525512345678", "OPEN",
                item("Maki", 1, null, new BigDecimal("99999999999999.99")));

        String response = orderTools.confirmOrder("525512345678", "DOMICILIO", "Calle Cinco", "Efectivo 500");
        entityManager.clear();

        assertThat(response).isEqualTo(
                "No se pudo procesar el carrito en este momento. Intenta nuevamente o solicita ayuda del restaurante.");
        assertThat(orderRepository.count()).isZero();
        assertThat(cartRepository.findById(cart.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
    }

    @Test
    void reopenCartClonesBothMoneyColumns() {
        Cart closed = persistCart("525512345678", "CLOSED", item("Old", 1, 5.0d, new BigDecimal("5.00")));
        Cart open = persistCart("525512345678", "OPEN", item("Maki", 2, 10.5d, new BigDecimal("10.50")));

        cartService.reopenCart("525512345678");
        entityManager.clear();

        Cart reopened = cartWithItems(closed.getId());
        assertThat(reopened.getStatus()).isEqualTo("OPEN");
        CartItem clone = reopened.getItems().stream()
                .filter(item -> item.getDishName().equals("Maki"))
                .findFirst()
                .orElseThrow();
        assertThat(clone.getUnitPrice()).isEqualTo(10.5d);
        assertThat(clone.getUnitPriceAmount()).isEqualByComparingTo("10.50");
        assertThat(cartRepository.findById(open.getId())).isEmpty();
    }

    @Test
    void duplicateIncomingRowsAccumulateIntoOneExistingClosedCartItem() {
        Cart closed = persistCart("525512345678", "CLOSED", item("Maki", 1, 10.5d, new BigDecimal("10.50")));
        Cart open = persistCart("525512345678", "OPEN",
                item("Maki", 2, 10.5d, new BigDecimal("10.50")),
                item("Maki", 3, 10.5d, new BigDecimal("10.50")));

        cartService.reopenCart("525512345678");
        entityManager.clear();

        Cart reopened = cartWithItems(closed.getId());
        assertThat(reopened.getStatus()).isEqualTo("OPEN");
        assertThat(reopened.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getDishName()).isEqualTo("Maki");
            assertThat(item.getQuantity()).isEqualTo(6);
            assertThat(item.getUnitPrice()).isEqualTo(10.5d);
            assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("10.50");
        });
        assertThat(cartRepository.findById(open.getId())).isEmpty();
    }

    @Test
    void duplicateIncomingRowsAccumulateIntoOneClonedCartItem() {
        Cart closed = persistCart("525512345678", "CLOSED", item("Old", 1, 5.0d, new BigDecimal("5.00")));
        Cart open = persistCart("525512345678", "OPEN",
                item("Maki", 2, 10.5d, new BigDecimal("10.50")),
                item("Maki", 3, 10.5d, new BigDecimal("10.50")));

        cartService.reopenCart("525512345678");
        entityManager.clear();

        Cart reopened = cartWithItems(closed.getId());
        assertThat(reopened.getStatus()).isEqualTo("OPEN");
        assertThat(reopened.getItems().stream().filter(item -> item.getDishName().equals("Maki")))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getQuantity()).isEqualTo(5);
                    assertThat(item.getUnitPrice()).isEqualTo(10.5d);
                    assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("10.50");
                });
        assertThat(cartRepository.findById(open.getId())).isEmpty();
    }

    @Test
    void duplicateIncomingRowsWithDifferentPricesRollBackBeforeMutation() {
        Cart closed = persistCart("525512345678", "CLOSED", item("Old", 1, 5.0d, new BigDecimal("5.00")));
        Cart open = persistCart("525512345678", "OPEN",
                item("Maki", 2, 10.5d, new BigDecimal("10.50")),
                item("Maki", 3, 11.0d, new BigDecimal("11.00")));

        assertThatThrownBy(() -> cartService.reopenCart("525512345678"))
                .isInstanceOf(CartReopenException.class)
                .extracting(exception -> ((CartReopenException) exception).getReason())
                .isEqualTo(CartReopenFailureReason.UNIT_PRICE_MISMATCH);
        entityManager.clear();

        Cart reloadedClosed = cartWithItems(closed.getId());
        Cart reloadedOpen = cartWithItems(open.getId());
        assertThat(reloadedClosed.getStatus()).isEqualTo("CLOSED");
        assertThat(reloadedClosed.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getDishName()).isEqualTo("Old");
            assertThat(item.getQuantity()).isEqualTo(1);
            assertThat(item.getUnitPrice()).isEqualTo(5.0d);
            assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("5.00");
        });
        assertThat(reloadedOpen.getStatus()).isEqualTo("OPEN");
        assertThat(reloadedOpen.getItems()).hasSize(2);
        assertThat(reloadedOpen.getItems().stream().filter(item -> item.getQuantity() == 2))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getDishName()).isEqualTo("Maki");
                    assertThat(item.getUnitPrice()).isEqualTo(10.5d);
                    assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("10.50");
                });
        assertThat(reloadedOpen.getItems().stream().filter(item -> item.getQuantity() == 3))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getDishName()).isEqualTo("Maki");
                    assertThat(item.getUnitPrice()).isEqualTo(11.0d);
                    assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("11.00");
                });
        assertThat(cartRepository.count()).isEqualTo(2);
        assertThat(countCartItems()).isEqualTo(3);
    }
    @Test
    void validThenInvalidReopenInputRollsBackEveryManagedMutation() {
        Cart closed = persistCart("525512345678", "CLOSED", item("Old", 1, 5.0d, new BigDecimal("5.00")));
        Cart open = persistCart("525512345678", "OPEN",
                item("Valid", 1, 10.5d, new BigDecimal("10.50")),
                item("Invalid", 1, null, new BigDecimal("99999999999999.99")));

        assertThatThrownBy(() -> cartService.reopenCart("525512345678"))
                .isInstanceOf(MonetaryCompatibilityException.class);
        entityManager.clear();

        Cart reloadedClosed = cartWithItems(closed.getId());
        Cart reloadedOpen = cartWithItems(open.getId());
        assertThat(reloadedClosed.getStatus()).isEqualTo("CLOSED");
        assertThat(reloadedClosed.getItems()).singleElement()
                .extracting(CartItem::getDishName)
                .isEqualTo("Old");
        assertThat(reloadedOpen.getStatus()).isEqualTo("OPEN");
        assertThat(reloadedOpen.getItems()).hasSize(2);
        assertThat(cartRepository.count()).isEqualTo(2);
    }

    @Test
    void sameDishPriceDisagreementLeavesBothCartsUnchanged() {
        Cart closed = persistCart("525512345678", "CLOSED", item("Maki", 1, 10.5d, new BigDecimal("10.50")));
        Cart open = persistCart("525512345678", "OPEN", item("Maki", 2, 11.0d, new BigDecimal("11.00")));

        assertThatThrownBy(() -> cartService.reopenCart("525512345678"))
                .isInstanceOf(CartReopenException.class)
                .extracting(exception -> ((CartReopenException) exception).getReason())
                .isEqualTo(CartReopenFailureReason.UNIT_PRICE_MISMATCH);
        entityManager.clear();

        Cart reloadedClosed = cartWithItems(closed.getId());
        Cart reloadedOpen = cartWithItems(open.getId());
        assertThat(reloadedClosed.getStatus()).isEqualTo("CLOSED");
        assertThat(reloadedClosed.getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getDishName()).isEqualTo("Maki");
                    assertThat(item.getQuantity()).isEqualTo(1);
                    assertThat(item.getUnitPrice()).isEqualTo(10.5d);
                    assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("10.50");
                });
        assertThat(reloadedOpen.getStatus()).isEqualTo("OPEN");
        assertThat(reloadedOpen.getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getDishName()).isEqualTo("Maki");
                    assertThat(item.getQuantity()).isEqualTo(2);
                    assertThat(item.getUnitPrice()).isEqualTo(11.0d);
                    assertThat(item.getUnitPriceAmount()).isEqualByComparingTo("11.00");
                });
        assertThat(cartRepository.count()).isEqualTo(2);
    }

    private Cart persistCart(String phoneNumber, String status, CartItem... items) {
        Cart cart = new Cart();
        cart.setPhoneNumber(phoneNumber);
        cart.setStatus(status);
        for (CartItem item : items) {
            cart.addItem(item);
        }
        return cartRepository.saveAndFlush(cart);
    }

    private CartItem item(String dishName, int quantity, Double unitPrice, BigDecimal unitPriceAmount) {
        CartItem item = new CartItem();
        item.setDishName(dishName);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setUnitPriceAmount(unitPriceAmount);
        return item;
    }

    private long countCartItems() {
        return entityManager.createQuery("select count(item) from CartItem item", Long.class).getSingleResult();
    }

    private Cart cartWithItems(Long cartId) {
        return entityManager.createQuery(
                        "select cart from Cart cart left join fetch cart.items where cart.id = :cartId",
                        Cart.class)
                .setParameter("cartId", cartId)
                .getSingleResult();
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
