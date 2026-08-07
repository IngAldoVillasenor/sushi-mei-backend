package com.sushimei.sushimei.backend.agent;

import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.CartService;
import com.sushimei.sushimei.backend.tools.OrderTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiConversationServiceTest {

    private static final String MEMORY_ID = "memory-1";
    private static final String PHONE_NUMBER = "5214770000001";
    private static final String GENERIC_MODEL_RESPONSE = "Hay algo mas en lo que pueda ayudarte?";

    @Mock
    private SushiAgent sushiAgent;

    @Mock
    private CatalogAgent catalogAgent;

    private final ConversationRetrievalPolicy retrievalPolicy = new ConversationRetrievalPolicy();

    @Test
    void simpleGreetingReturnsANaturalResponseWithoutInvokingEitherAgent() {
        String response = service(new AiToolSafetyGuard()).chat(MEMORY_ID, PHONE_NUMBER, "Hola");

        assertThat(response).contains("Sushi Mei");
        verifyNoInteractions(sushiAgent, catalogAgent);
    }

    @Test
    void ambiguousAddPronounReturnsClarificationWithoutInvokingEitherAgent() {
        String response = service(new AiToolSafetyGuard()).chat(MEMORY_ID, PHONE_NUMBER, "Agregamelo");

        assertThat(response).contains("producto");
        verifyNoInteractions(sushiAgent, catalogAgent);
    }

    @Test
    void ambiguousRemovePronounReturnsClarificationWithoutInvokingEitherAgent() {
        String response = service(new AiToolSafetyGuard()).chat(MEMORY_ID, PHONE_NUMBER, "Quitalo");

        assertThat(response).contains("producto");
        verifyNoInteractions(sushiAgent, catalogAgent);
    }

    @Test
    void finishIntentReturnsSafeAcknowledgementWithoutInvokingEitherAgent() {
        String response = service(new AiToolSafetyGuard()).chat(MEMORY_ID, PHONE_NUMBER, "Ya seria todo");

        assertThat(response).doesNotContain("procesada").doesNotContain("confirmada");
        verifyNoInteractions(sushiAgent, catalogAgent);
    }

    @Test
    void menuQuestionUsesTheToolFreeCatalogAgent() {
        when(catalogAgent.chat("Que venden?")).thenReturn("Tenemos sushi y bebidas.");

        String response = service(new AiToolSafetyGuard()).chat(MEMORY_ID, PHONE_NUMBER, "Que venden?");

        assertThat(response).isEqualTo("Tenemos sushi y bebidas.");
        verify(catalogAgent).chat("Que venden?");
        verifyNoInteractions(sushiAgent);
    }

    @Test
    void priceQuestionUsesTheToolFreeCatalogAgent() {
        when(catalogAgent.chat("Cuanto cuesta California?")).thenReturn("El California cuesta 100.");

        String response = service(new AiToolSafetyGuard()).chat(MEMORY_ID, PHONE_NUMBER, "Cuanto cuesta California?");

        assertThat(response).isEqualTo("El California cuesta 100.");
        verify(catalogAgent).chat("Cuanto cuesta California?");
        verifyNoInteractions(sushiAgent);
    }

    @Test
    void catalogResponseCannotClaimACartMutationOrOrderCompletion() {
        when(catalogAgent.chat("Que venden?")).thenReturn("El California ha sido agregado a tu pedido.");

        String response = service(new AiToolSafetyGuard()).chat(MEMORY_ID, PHONE_NUMBER, "Que venden?");

        assertThat(response).contains("Puedo ayudarte");
        verify(catalogAgent).chat("Que venden?");
        verifyNoInteractions(sushiAgent);
    }

    @Test
    void successfulAddReturnsItsAuthoritativeToolResponseInsteadOfGenericModelText() {
        CartService cartService = mock(CartService.class);
        when(cartService.getCartContents(PHONE_NUMBER)).thenReturn("Detalle exacto del carrito");
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools tools = new OrderTools(mock(OrderRepository.class), cartService, guard);
        when(sushiAgent.chat(MEMORY_ID, PHONE_NUMBER, "Quiero un California"))
                .thenAnswer(invocation -> {
                    tools.addDishToCart(PHONE_NUMBER, "California Roll", 1, 79.0);
                    return GENERIC_MODEL_RESPONSE;
                });

        String response = service(guard).chat(MEMORY_ID, PHONE_NUMBER, "Quiero un California");

        assertThat(response).contains("1 x California Roll", "Detalle exacto del carrito")
                .doesNotContain(GENERIC_MODEL_RESPONSE);
        verify(cartService).addItem(PHONE_NUMBER, "California Roll", 1, 79.0);
        verifyNoInteractions(catalogAgent);
    }

    @Test
    void successfulRemoveReturnsItsAuthoritativeToolResponseInsteadOfGenericModelText() {
        CartService cartService = mock(CartService.class);
        when(cartService.removeItem(PHONE_NUMBER, "Coca Cola", 1)).thenReturn("El carrito est\u00e1 vac\u00edo.");
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools tools = new OrderTools(mock(OrderRepository.class), cartService, guard);
        when(sushiAgent.chat(MEMORY_ID, PHONE_NUMBER, "Quita la Coca"))
                .thenAnswer(invocation -> {
                    tools.removeDishFromCart(PHONE_NUMBER, "Coca Cola", 1);
                    return GENERIC_MODEL_RESPONSE;
                });

        String response = service(guard).chat(MEMORY_ID, PHONE_NUMBER, "Quita la Coca");

        assertThat(response).contains("1 x Coca Cola", "El carrito est\u00e1 vac\u00edo")
                .doesNotContain(GENERIC_MODEL_RESPONSE);
        verify(cartService).removeItem(PHONE_NUMBER, "Coca Cola", 1);
        verifyNoInteractions(catalogAgent);
    }

    @Test
    void successfulCartQueryReturnsAuthoritativeContentsInsteadOfGenericModelText() {
        CartService cartService = mock(CartService.class);
        String authoritativeCartContents = "Detalle exacto de la orden:\n- 1x California Roll\nTOTAL A PAGAR: $79.0 MXN";
        when(cartService.getCartContents(PHONE_NUMBER)).thenReturn(authoritativeCartContents);
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools tools = new OrderTools(mock(OrderRepository.class), cartService, guard);
        when(sushiAgent.chat(MEMORY_ID, PHONE_NUMBER, "Que llevo?"))
                .thenAnswer(invocation -> {
                    tools.checkCart(PHONE_NUMBER);
                    return GENERIC_MODEL_RESPONSE;
                });

        String response = service(guard).chat(MEMORY_ID, PHONE_NUMBER, "Que llevo?");

        assertThat(response).isEqualTo(authoritativeCartContents).doesNotContain(GENERIC_MODEL_RESPONSE);
        verify(cartService).getCartContents(PHONE_NUMBER);
        verifyNoInteractions(catalogAgent);
    }

    @Test
    void blockedAddCannotBecomeAModelSuccessClaim() {
        CartService cartService = mock(CartService.class);
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools tools = new OrderTools(mock(OrderRepository.class), cartService, guard);
        when(sushiAgent.chat(MEMORY_ID, PHONE_NUMBER, "Necesito ayuda"))
                .thenAnswer(invocation -> {
                    tools.addDishToCart(PHONE_NUMBER, "California Roll", 1, 100.0);
                    return "El California roll ha sido agregado a tu pedido.";
                });

        String response = service(guard).chat(MEMORY_ID, PHONE_NUMBER, "Necesito ayuda");

        assertThat(response).contains("producto");
        verifyNoInteractions(cartService, catalogAgent);
    }

    @Test
    void blockedRemoveCannotBecomeAModelSuccessClaim() {
        CartService cartService = mock(CartService.class);
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools tools = new OrderTools(mock(OrderRepository.class), cartService, guard);
        when(sushiAgent.chat(MEMORY_ID, PHONE_NUMBER, "Necesito ayuda"))
                .thenAnswer(invocation -> {
                    tools.removeDishFromCart(PHONE_NUMBER, "California Roll", 1);
                    return "El California roll fue eliminado de tu pedido.";
                });

        String response = service(guard).chat(MEMORY_ID, PHONE_NUMBER, "Necesito ayuda");

        assertThat(response).contains("producto");
        verifyNoInteractions(cartService, catalogAgent);
    }

    @Test
    void failedMutationCannotBecomeAModelSuccessClaim() {
        CartService cartService = mock(CartService.class);
        doThrow(new IllegalArgumentException("invalid money"))
                .when(cartService).addItem(anyString(), anyString(), anyInt(), anyDouble());
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools tools = new OrderTools(mock(OrderRepository.class), cartService, guard);
        when(sushiAgent.chat(MEMORY_ID, PHONE_NUMBER, "Quiero un California"))
                .thenAnswer(invocation -> {
                    tools.addDishToCart(PHONE_NUMBER, "California Roll", 1, 100.0);
                    return "El California roll ha sido agregado a tu pedido.";
                });

        String response = service(guard).chat(MEMORY_ID, PHONE_NUMBER, "Quiero un California");

        assertThat(response).contains("No se pudo modificar");
        verify(cartService).addItem(PHONE_NUMBER, "California Roll", 1, 100.0);
        verifyNoInteractions(catalogAgent);
    }

    @Test
    void unsuccessfulRemoveResultCannotBecomeAModelSuccessClaim() {
        CartService cartService = mock(CartService.class);
        when(cartService.removeItem(PHONE_NUMBER, "California Roll", 1))
                .thenReturn("Error interno: item unavailable");
        AiToolSafetyGuard guard = new AiToolSafetyGuard();
        OrderTools tools = new OrderTools(mock(OrderRepository.class), cartService, guard);
        when(sushiAgent.chat(MEMORY_ID, PHONE_NUMBER, "Quita el California"))
                .thenAnswer(invocation -> {
                    tools.removeDishFromCart(PHONE_NUMBER, "California Roll", 1);
                    return "El California roll fue eliminado de tu pedido.";
                });

        String response = service(guard).chat(MEMORY_ID, PHONE_NUMBER, "Quita el California");

        assertThat(response).contains("No se pudo modificar");
        verify(cartService).removeItem(PHONE_NUMBER, "California Roll", 1);
        verifyNoInteractions(catalogAgent);
    }

    private AiConversationService service(AiToolSafetyGuard guard) {
        return new AiConversationService(sushiAgent, catalogAgent, guard, retrievalPolicy);
    }
}