package com.sushimei.sushimei.backend.checkout;

import com.sushimei.sushimei.backend.conversation.ConversationSession;
import com.sushimei.sushimei.backend.conversation.ConversationSessionRepository;
import com.sushimei.sushimei.backend.conversation.ConversationState;
import com.sushimei.sushimei.backend.conversation.ConversationTransitionService;
import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.entity.OrderSource;
import com.sushimei.sushimei.backend.repository.CartRepository;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, OrderServiceIntegrationTest.TestInfrastructureConfiguration.class,
        OrderServiceIntegrationTest.FixedClockConfiguration.class})
class OrderServiceIntegrationTest {

    private static final Instant COMPLETION_TIME = Instant.parse("2026-08-07T15:00:00Z");

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ConversationSessionRepository conversationSessionRepository;

    @Autowired
    private ConversationTransitionService conversationTransitionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearFixtures() {
        jdbcTemplate.update("delete from public.order_lines");
        jdbcTemplate.update("delete from public.orders");
        jdbcTemplate.update("delete from public.cart_items");
        jdbcTemplate.update("delete from public.cart");
        jdbcTemplate.update("delete from public.conversation_sessions");
    }

    @Test
    void completesDeliveryCashWithStructuredLinesExactTotalsAndLegacyCompatibility() {
        String phoneNumber = "5214770000101";
        Cart cart = persistOpenCart(phoneNumber,
                item("Coca Cola", 1, "20.00"),
                item("California Roll", 2, "79.00"));
        readyDeliveryCash(phoneNumber, "Calle 123", "200.00");

        CheckoutCompletionResult result = orderService.completeCheckout(command(phoneNumber, cart.getId()));

        assertThat(result.outcome()).isEqualTo(CheckoutCompletionOutcome.CREATED);
        OrderRecord order = orderRepository.findById(result.orderId()).orElseThrow();
        assertThat(order.getPhoneNumber()).isEqualTo(phoneNumber);
        assertThat(order.getSourceCartId()).isEqualTo(cart.getId());
        assertThat(order.getOrderSource()).isEqualTo(OrderSource.WHATSAPP_AI);
        assertThat(order.getFulfillmentType()).isEqualTo(OrderFulfillmentType.DELIVERY);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.CASH);
        assertThat(order.getDeliveryType()).isEqualTo("DOMICILIO");
        assertThat(order.getDeliveryAddress()).isEqualTo("Calle 123");
        assertThat(order.getPickupName()).isNull();
        assertThat(order.getCashDenomination()).isEqualByComparingTo("200.00");
        assertThat(order.getTransferReceiptPath()).isNull();
        assertThat(order.getTotalAmountAmount()).isEqualByComparingTo("178.00");
        assertThat(order.getTotalAmount()).isEqualTo(178.00d);
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getCreatedAt()).isEqualTo(COMPLETION_TIME.atOffset(ZoneOffset.UTC).toLocalDateTime());
        assertThat(order.getOrderDetails()).isEqualTo("""
                Detalle exacto de la orden:
                - 1x Coca Cola ($20.00 c/u) = $20.00
                - 2x California Roll ($79.00 c/u) = $158.00

                TOTAL A PAGAR: $178.00 MXN""");

        List<Long> sourceItemIds = jdbcTemplate.queryForList(
                "select source_cart_item_id from public.order_lines where order_id = ? order by line_position",
                Long.class, order.getId());
        List<Integer> positions = jdbcTemplate.queryForList(
                "select line_position from public.order_lines where order_id = ? order by line_position",
                Integer.class, order.getId());
        assertThat(sourceItemIds).containsExactlyElementsOf(cart.getItems().stream().map(CartItem::getId).sorted().toList());
        assertThat(positions).containsExactly(1, 2);
        assertThat(jdbcTemplate.queryForList(
                "select line_total_amount from public.order_lines where order_id = ? order by line_position",
                BigDecimal.class, order.getId())).containsExactly(new BigDecimal("20.00"), new BigDecimal("158.00"));
        assertClosedAndConfirmed(cart.getId(), phoneNumber);
    }

    @Test
    void completesDeliveryTransfer() {
        String phoneNumber = "5214770000102";
        Cart cart = persistOpenCart(phoneNumber, item("Maki", 1, "10.50"));
        readyDeliveryTransfer(phoneNumber, "Calle 456", "receipts/transfer.png");

        OrderRecord order = completedOrder(phoneNumber, cart.getId());

        assertThat(order.getFulfillmentType()).isEqualTo(OrderFulfillmentType.DELIVERY);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.TRANSFER);
        assertThat(order.getTransferReceiptPath()).isEqualTo("receipts/transfer.png");
        assertThat(order.getCashDenomination()).isNull();
        assertClosedAndConfirmed(cart.getId(), phoneNumber);
    }

    @Test
    void completesPickupCashAndPickupTransfer() {
        String cashPhone = "5214770000103";
        Cart cashCart = persistOpenCart(cashPhone, item("Maki", 1, "10.50"));
        readyPickupCash(cashPhone, "Li", "100.00");
        OrderRecord cashOrder = completedOrder(cashPhone, cashCart.getId());
        assertThat(cashOrder.getFulfillmentType()).isEqualTo(OrderFulfillmentType.PICKUP);
        assertThat(cashOrder.getPaymentMethod()).isEqualTo(OrderPaymentMethod.CASH);
        assertThat(cashOrder.getPickupName()).isEqualTo("Li");
        assertThat(cashOrder.getDeliveryAddress()).isNull();

        String transferPhone = "5214770000104";
        Cart transferCart = persistOpenCart(transferPhone, item("Coca Cola", 1, "20.00"));
        readyPickupTransfer(transferPhone, "Ana", "receipts/pickup.png");
        OrderRecord transferOrder = completedOrder(transferPhone, transferCart.getId());
        assertThat(transferOrder.getFulfillmentType()).isEqualTo(OrderFulfillmentType.PICKUP);
        assertThat(transferOrder.getPaymentMethod()).isEqualTo(OrderPaymentMethod.TRANSFER);
        assertThat(transferOrder.getPickupName()).isEqualTo("Ana");
        assertThat(transferOrder.getTransferReceiptPath()).isEqualTo("receipts/pickup.png");
    }

    @Test
    void completesPickupCard() {
        String phoneNumber = "5214770000105";
        Cart cart = persistOpenCart(phoneNumber, item("California Roll", 1, "79.00"));
        readyPickupCard(phoneNumber, "Jo");

        OrderRecord order = completedOrder(phoneNumber, cart.getId());

        assertThat(order.getFulfillmentType()).isEqualTo(OrderFulfillmentType.PICKUP);
        assertThat(order.getPaymentMethod()).isEqualTo(OrderPaymentMethod.CARD);
        assertThat(order.getCashDenomination()).isNull();
        assertThat(order.getTransferReceiptPath()).isNull();
        assertClosedAndConfirmed(cart.getId(), phoneNumber);
    }

    @Test
    void retryReturnsTheExistingOrderWithoutClosingOrTransitioningAgain() {
        String phoneNumber = "5214770000106";
        Cart cart = persistOpenCart(phoneNumber, item("Maki", 1, "10.50"));
        readyPickupCard(phoneNumber, "Li");

        CheckoutCompletionResult created = orderService.completeCheckout(command(phoneNumber, cart.getId()));
        Long versionAfterCreation = conversationSessionRepository.findById(phoneNumber).orElseThrow().getVersion();
        CheckoutCompletionResult retry = orderService.completeCheckout(command(phoneNumber, cart.getId()));

        assertThat(retry.outcome()).isEqualTo(CheckoutCompletionOutcome.ALREADY_COMPLETED);
        assertThat(retry.orderId()).isEqualTo(created.orderId());
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(conversationSessionRepository.findById(phoneNumber).orElseThrow().getVersion())
                .isEqualTo(versionAfterCreation);
        assertClosedAndConfirmed(cart.getId(), phoneNumber);
    }

    @Test
    void rejectsAnIdempotentRetryWhoseOrderSourceDoesNotMatchTheExistingOrder() {
        String phoneNumber = "52147700001065";
        Cart cart = persistOpenCart(phoneNumber, item("Maki", 1, "10.50"));
        readyPickupCard(phoneNumber, "Li");
        completedOrder(phoneNumber, cart.getId());

        assertThatThrownBy(() -> orderService.completeCheckout(new CheckoutCompletionCommand(
                phoneNumber, cart.getId(), OrderSource.COUNTER)))
                .isInstanceOf(CheckoutCompletionException.class)
                .extracting(exception -> ((CheckoutCompletionException) exception).getReason())
                .isEqualTo(CheckoutCompletionFailureReason.IDEMPOTENCY_INCOMPATIBLE_ORDER);
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsMismatchedPhoneWithoutLeakingAnotherCustomersIdempotentOrder() {
        String ownerPhone = "5214770000107";
        Cart cart = persistOpenCart(ownerPhone, item("Maki", 1, "10.50"));
        readyPickupCard(ownerPhone, "Li");
        completedOrder(ownerPhone, cart.getId());

        assertThatThrownBy(() -> orderService.completeCheckout(command("5214770000999", cart.getId())))
                .isInstanceOf(CheckoutCompletionException.class)
                .extracting(exception -> ((CheckoutCompletionException) exception).getReason())
                .isEqualTo(CheckoutCompletionFailureReason.IDEMPOTENCY_PHONE_MISMATCH);
    }

    @Test
    void rejectsUnknownWrongOwnerClosedAndEmptyExactCarts() {
        String phoneNumber = "5214770000108";
        readyPickupCard(phoneNumber, "Li");
        assertThatThrownBy(() -> orderService.completeCheckout(command(phoneNumber, 999999L)))
                .isInstanceOf(CheckoutCompletionException.class)
                .extracting(exception -> ((CheckoutCompletionException) exception).getReason())
                .isEqualTo(CheckoutCompletionFailureReason.CART_NOT_FOUND);

        Cart otherOwner = persistOpenCart("5214770000998", item("Maki", 1, "10.50"));
        assertThatThrownBy(() -> orderService.completeCheckout(command(phoneNumber, otherOwner.getId())))
                .isInstanceOf(CheckoutCompletionException.class)
                .extracting(exception -> ((CheckoutCompletionException) exception).getReason())
                .isEqualTo(CheckoutCompletionFailureReason.CART_PHONE_MISMATCH);

        Cart closed = persistOpenCart(phoneNumber, item("Maki", 1, "10.50"));
        closed.setStatus("CLOSED");
        cartRepository.saveAndFlush(closed);
        assertThatThrownBy(() -> orderService.completeCheckout(command(phoneNumber, closed.getId())))
                .isInstanceOf(CheckoutCompletionException.class)
                .extracting(exception -> ((CheckoutCompletionException) exception).getReason())
                .isEqualTo(CheckoutCompletionFailureReason.CART_NOT_OPEN);

        Cart empty = persistOpenCart(phoneNumber);
        assertThatThrownBy(() -> orderService.completeCheckout(command(phoneNumber, empty.getId())))
                .isInstanceOf(EmptyCartException.class);
    }

    @Test
    void missingSessionAndWrongSessionStateLeaveTheExactCartOpenWithoutOrders() {
        String missingSessionPhone = "5214770000109";
        Cart missingSessionCart = persistOpenCart(missingSessionPhone, item("Maki", 1, "10.50"));
        assertThatThrownBy(() -> orderService.completeCheckout(command(missingSessionPhone, missingSessionCart.getId())))
                .isInstanceOf(CheckoutCompletionException.class)
                .extracting(exception -> ((CheckoutCompletionException) exception).getReason())
                .isEqualTo(CheckoutCompletionFailureReason.CONVERSATION_SESSION_NOT_FOUND);
        assertThat(cartRepository.findById(missingSessionCart.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");

        String orderingPhone = "5214770000110";
        Cart orderingCart = persistOpenCart(orderingPhone, item("Maki", 1, "10.50"));
        jdbcTemplate.update("""
                        insert into public.conversation_sessions (
                            phone_number, state, created_at, updated_at, last_activity_at, version
                        ) values (?, 'ORDERING', ?, ?, ?, 0)
                        """, orderingPhone, Timestamp.from(COMPLETION_TIME), Timestamp.from(COMPLETION_TIME),
                Timestamp.from(COMPLETION_TIME));
        assertThatThrownBy(() -> orderService.completeCheckout(command(orderingPhone, orderingCart.getId())))
                .isInstanceOf(RuntimeException.class);
        assertRollbackState(orderingCart.getId(), orderingPhone);
    }

    @Test
    void corruptedReadySessionRollsBackThePreparedOrderCartClosureAndSessionTransition() {
        String phoneNumber = "5214770000111";
        Cart cart = persistOpenCart(phoneNumber, item("Maki", 1, "10.50"));
        jdbcTemplate.update("""
                        insert into public.conversation_sessions (
                            phone_number, state, created_at, updated_at, last_activity_at, version
                        ) values (?, 'READY_TO_CONFIRM', ?, ?, ?, 0)
                        """, phoneNumber, Timestamp.from(COMPLETION_TIME), Timestamp.from(COMPLETION_TIME),
                Timestamp.from(COMPLETION_TIME));

        assertThatThrownBy(() -> orderService.completeCheckout(command(phoneNumber, cart.getId())))
                .isInstanceOf(RuntimeException.class);

        assertRollbackState(cart.getId(), phoneNumber);
    }

    private OrderRecord completedOrder(String phoneNumber, Long cartId) {
        CheckoutCompletionResult result = orderService.completeCheckout(command(phoneNumber, cartId));
        assertThat(result.outcome()).isEqualTo(CheckoutCompletionOutcome.CREATED);
        return orderRepository.findById(result.orderId()).orElseThrow();
    }

    private CheckoutCompletionCommand command(String phoneNumber, Long cartId) {
        return new CheckoutCompletionCommand(phoneNumber, cartId, OrderSource.WHATSAPP_AI);
    }

    private Cart persistOpenCart(String phoneNumber, CartItem... items) {
        Cart cart = new Cart();
        cart.setPhoneNumber(phoneNumber);
        cart.setStatus("OPEN");
        for (CartItem item : items) {
            cart.addItem(item);
        }
        return cartRepository.saveAndFlush(cart);
    }

    private CartItem item(String dishName, int quantity, String unitPrice) {
        CartItem item = new CartItem();
        item.setDishName(dishName);
        item.setQuantity(quantity);
        item.setUnitPrice(Double.valueOf(unitPrice));
        item.setUnitPriceAmount(new BigDecimal(unitPrice));
        return item;
    }

    private void readyDeliveryCash(String phoneNumber, String address, String cash) {
        conversationTransitionService.requestCheckoutReview(phoneNumber);
        conversationTransitionService.confirmCart(phoneNumber);
        conversationTransitionService.selectDelivery(phoneNumber);
        conversationTransitionService.provideDeliveryAddress(phoneNumber, address);
        conversationTransitionService.selectCash(phoneNumber);
        conversationTransitionService.provideCashDenomination(phoneNumber, new BigDecimal(cash));
    }

    private void readyDeliveryTransfer(String phoneNumber, String address, String receiptPath) {
        conversationTransitionService.requestCheckoutReview(phoneNumber);
        conversationTransitionService.confirmCart(phoneNumber);
        conversationTransitionService.selectDelivery(phoneNumber);
        conversationTransitionService.provideDeliveryAddress(phoneNumber, address);
        conversationTransitionService.selectTransfer(phoneNumber);
        conversationTransitionService.provideTransferReceipt(phoneNumber, receiptPath);
    }

    private void readyPickupCash(String phoneNumber, String pickupName, String cash) {
        conversationTransitionService.requestCheckoutReview(phoneNumber);
        conversationTransitionService.confirmCart(phoneNumber);
        conversationTransitionService.selectPickup(phoneNumber);
        conversationTransitionService.providePickupName(phoneNumber, pickupName);
        conversationTransitionService.selectCash(phoneNumber);
        conversationTransitionService.provideCashDenomination(phoneNumber, new BigDecimal(cash));
    }

    private void readyPickupTransfer(String phoneNumber, String pickupName, String receiptPath) {
        conversationTransitionService.requestCheckoutReview(phoneNumber);
        conversationTransitionService.confirmCart(phoneNumber);
        conversationTransitionService.selectPickup(phoneNumber);
        conversationTransitionService.providePickupName(phoneNumber, pickupName);
        conversationTransitionService.selectTransfer(phoneNumber);
        conversationTransitionService.provideTransferReceipt(phoneNumber, receiptPath);
    }

    private void readyPickupCard(String phoneNumber, String pickupName) {
        conversationTransitionService.requestCheckoutReview(phoneNumber);
        conversationTransitionService.confirmCart(phoneNumber);
        conversationTransitionService.selectPickup(phoneNumber);
        conversationTransitionService.providePickupName(phoneNumber, pickupName);
        conversationTransitionService.selectCard(phoneNumber);
    }

    private void assertClosedAndConfirmed(Long cartId, String phoneNumber) {
        assertThat(cartRepository.findById(cartId).orElseThrow().getStatus()).isEqualTo("CLOSED");
        assertThat(conversationSessionRepository.findById(phoneNumber).orElseThrow().getState())
                .isEqualTo(ConversationState.ORDER_CONFIRMED);
    }

    private void assertRollbackState(Long cartId, String phoneNumber) {
        assertThat(orderRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.order_lines", Integer.class)).isZero();
        assertThat(cartRepository.findById(cartId).orElseThrow().getStatus()).isEqualTo("OPEN");
        assertThat(conversationSessionRepository.findById(phoneNumber).orElseThrow().getState())
                .isNotEqualTo(ConversationState.ORDER_CONFIRMED);
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(COMPLETION_TIME, ZoneOffset.UTC);
        }
    }
}
