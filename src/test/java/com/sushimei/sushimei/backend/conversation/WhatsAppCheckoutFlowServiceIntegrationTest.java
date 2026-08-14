package com.sushimei.sushimei.backend.conversation;

import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.orderread.OperationalOrderReadService;
import com.sushimei.sushimei.backend.repository.CartRepository;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class,
        WhatsAppCheckoutFlowServiceIntegrationTest.TestInfrastructureConfiguration.class})
class WhatsAppCheckoutFlowServiceIntegrationTest {

    @Autowired private WhatsAppCheckoutFlowService flowService;
    @Autowired private ConversationSessionService sessionService;
    @Autowired private CartRepository cartRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OperationalOrderReadService operationalOrderReadService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from public.order_line_selection_snapshots");
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
        jdbcTemplate.update("delete from public.cart_items");
        jdbcTemplate.update("delete from public.cart");
        jdbcTemplate.update("delete from public.conversation_sessions");
    }

    @Test
    void pickupCardConfirmationCreatesOnePendingWhatsAppOrderVisibleToKitchen() {
        String phone = "525512340001";
        Cart cart = openCart(phone, item("California Roll", 2, "79.00"));

        assertThat(text(phone, "ya sería todo"))
                .contains("2x California Roll", "Total: $158.00", "carrito está correcto");
        assertThat(text(phone, "sí"))
                .contains("domicilio", "recoger");
        assertThat(text(phone, "para recoger"))
                .contains("nombre");
        assertThat(text(phone, "Aldo"))
                .contains("efectivo", "transferencia", "tarjeta");
        assertThat(text(phone, "tarjeta"))
                .contains("Tarjeta al recoger", "confirmar");
        assertThat(text(phone, "confirmar"))
                .contains("Orden #", "Nuevos pedidos");

        OrderRecord order = orderRepository.findBySourceCartId(cart.getId()).orElseThrow();
        assertThat(order.getOrderSource()).isEqualTo(OrderSource.WHATSAPP_AI);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.CARD);
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getPickupName()).isEqualTo("Aldo");
        assertThat(cartRepository.findById(cart.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
        assertThat(sessionService.findSession(phone).orElseThrow().getState())
                .isEqualTo(ConversationState.ORDER_CONFIRMED);
        assertThat(operationalOrderReadService.activeOrders())
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.id()).isEqualTo(order.getId());
                    assertThat(summary.orderSource()).isEqualTo(OrderSource.WHATSAPP_AI);
                    assertThat(summary.status()).isEqualTo("PENDING");
                });
    }

    @Test
    void deliveryTransferRequiresImageThenCreatesPendingValidationOrder() {
        String phone = "525512340002";
        Cart cart = openCart(phone, item("Philadelphia Roll", 1, "89.00"));

        text(phone, "ya terminé");
        text(phone, "correcto");
        assertThat(text(phone, "entrega a domicilio")).contains("dirección completa");
        assertThat(text(phone, "Calle Principal 123, León")).contains("efectivo", "transferencia");
        assertThat(text(phone, "transferencia")).contains("imagen", "comprobante");
        assertThat(text(phone, "ya pagué")).contains("Envíame una imagen");
        assertThat(flowService.handleImage(phone, "receipts/transfer.jpg").orElseThrow())
                .contains("comprobante recibido", "confirmar");
        assertThat(text(phone, "sí, confirmar")).contains("pendiente de validar tu transferencia");

        OrderRecord order = orderRepository.findBySourceCartId(cart.getId()).orElseThrow();
        assertThat(order.getOrderSource()).isEqualTo(OrderSource.WHATSAPP_AI);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.TRANSFER);
        assertThat(order.getTransferReceiptPath()).isEqualTo("receipts/transfer.jpg");
        assertThat(order.getStatus()).isEqualTo("PENDING_VALIDATION");
        assertThat(order.getDeliveryAddress()).isEqualTo("Calle Principal 123, León");
        assertThat(operationalOrderReadService.activeOrders())
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.status()).isEqualTo("PENDING_VALIDATION");
                    assertThat(summary.requiresPaymentValidation()).isTrue();
                });
    }

    @Test
    void cashBelowTotalDoesNotAdvanceAndEmptyCartDoesNotStartCheckout() {
        String phone = "525512340003";
        openCart(phone, item("Banana Roll", 1, "79.00"));

        text(phone, "eso sería todo");
        text(phone, "sí");
        text(phone, "recoger");
        text(phone, "Li");
        text(phone, "efectivo");

        assertThat(text(phone, "pago con 50"))
                .contains("menor al total de $79.00");
        assertThat(sessionService.findSession(phone).orElseThrow().getState())
                .isEqualTo(ConversationState.WAITING_CASH_DENOMINATION);
        assertThat(text(phone, "pago con 1,000"))
                .contains("Efectivo: $1000.00", "confirmar");

        String emptyPhone = "525512340004";
        assertThat(text(emptyPhone, "ya sería todo"))
                .contains("carrito está vacío");
        assertThat(sessionService.findSession(emptyPhone).orElseThrow().getState())
                .isEqualTo(ConversationState.ORDERING);
    }

    @Test
    void explicitCartResetClosesTheAbandonedCartAndRestartsOrdering() {
        String phone = "525512340005";
        Cart abandonedCart = openCart(phone, item("Empanizado ebi", 3, "99.00"));
        text(phone, "ya sería todo");
        assertThat(sessionService.findSession(phone).orElseThrow().getState())
                .isEqualTo(ConversationState.WAITING_CART_CONFIRMATION);

        assertThat(text(phone, "Puedes vaciar el carrito por favor"))
                .contains("vacié tu carrito");

        assertThat(cartRepository.findById(abandonedCart.getId()).orElseThrow().getStatus())
                .isEqualTo("CLOSED");
        assertThat(sessionService.findSession(phone).orElseThrow().getState())
                .isEqualTo(ConversationState.ORDERING);
        assertThat(text(phone, "ya sería todo"))
                .contains("carrito está vacío");
    }

    private String text(String phone, String message) {
        return flowService.handleText(phone, message).orElseThrow();
    }

    private Cart openCart(String phone, CartItem... items) {
        Cart cart = new Cart();
        cart.setPhoneNumber(phone);
        cart.setStatus("OPEN");
        for (CartItem item : items) {
            cart.addItem(item);
        }
        return cartRepository.saveAndFlush(cart);
    }

    private CartItem item(String name, int quantity, String price) {
        CartItem item = new CartItem();
        item.setDishName(name);
        item.setQuantity(quantity);
        item.setUnitPrice(Double.valueOf(price));
        item.setUnitPriceAmount(new BigDecimal(price));
        return item;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        @Primary
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        @Bean
        @Primary
        ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
