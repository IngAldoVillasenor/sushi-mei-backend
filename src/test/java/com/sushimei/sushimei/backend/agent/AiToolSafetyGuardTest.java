package com.sushimei.sushimei.backend.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiToolSafetyGuardTest {

    private final AiToolSafetyGuard guard = new AiToolSafetyGuard();

    @Test
    void greetingCannotAddAnUnrequestedProduct() {
        assertBlocked("Hola", () -> guard.requireAddAllowed("Ramen Tonkotsu"), AiToolSafetyReason.ADD_NOT_EXPLICITLY_REQUESTED);
    }

    @Test
    void menuQuestionCannotReadTheCart() {
        assertBlocked("¿Qué venden?", guard::requireCartQueryAllowed, AiToolSafetyReason.CART_QUERY_NOT_REQUESTED);
    }

    @Test
    void explicitlyRequestedProductCanBeAdded() {
        guard.withinTextTurn("Quiero un California", () -> {
            guard.requireAddAllowed("California Roll");
            return null;
        });
    }

    @Test
    void naturalFollowUpCanAddTheExactNamedPresentation() {
        guard.withinTextTurn("Y una Coca de 1.75 L", () -> {
            guard.requireAddAllowed("Coca 1.75 ml (Refresco)");
            return null;
        });
    }

    @Test
    void naturalFollowUpCannotSelectADifferentSizeOrAmbiguousFamilyVariant() {
        assertBlocked("Y una Coca de 1.75 L", () -> guard.requireAddAllowed("Coca 600 ml (Refresco)"),
                AiToolSafetyReason.ADD_NOT_EXPLICITLY_REQUESTED);
        assertBlocked("Una Charola Familiar por favor", () -> guard.requireAddAllowed("Clásica Familiar"),
                AiToolSafetyReason.ADD_NOT_EXPLICITLY_REQUESTED);
    }

    @Test
    void orderingVerbCanAddTheNamedProduct() {
        guard.withinTextTurn("Deseo ordenar una Clásica Familiar", () -> {
            guard.requireAddAllowed("Clásica Familiar");
            return null;
        });
    }

    @Test
    void genericCategoriesCannotSelectArbitraryProducts() {
        assertBlocked("Ponme un rollo y una bebida", () -> guard.requireAddAllowed("Rollo Empanizado"),
                AiToolSafetyReason.ADD_NOT_EXPLICITLY_REQUESTED);
    }

    @Test
    void contextualPronounCannotSelectAnUnrelatedProduct() {
        assertBlocked("Agrégamelo", () -> guard.requireAddAllowed("Aderezo ranch"),
                AiToolSafetyReason.ADD_NOT_EXPLICITLY_REQUESTED);
    }

    @Test
    void cartQueryIsAllowedExactlyOnceForTheTurn() {
        guard.withinTextTurn("¿Qué llevo?", () -> {
            guard.requireCartQueryAllowed();
            assertThatThrownBy(guard::requireCartQueryAllowed)
                    .isInstanceOfSatisfying(AiToolSafetyException.class,
                            exception -> org.assertj.core.api.Assertions.assertThat(exception.getReason())
                                    .isEqualTo(AiToolSafetyReason.CART_QUERY_ALREADY_PERFORMED));
            return null;
        });
    }

    @Test
    void removalRequiresTheNamedProductInTheCurrentMessage() {
        guard.withinTextTurn("Quita la Coca", () -> {
            guard.requireRemoveAllowed("Coca Cola");
            return null;
        });
        assertBlocked("Quita la Coca", () -> guard.requireRemoveAllowed("Ramen Tonkotsu"),
                AiToolSafetyReason.REMOVE_NOT_EXPLICITLY_REQUESTED);
    }

    @Test
    void finishIntentCannotUseCartOrOrderTools() {
        assertBlocked("Ya sería todo", guard::requireCartQueryAllowed, AiToolSafetyReason.CART_QUERY_NOT_REQUESTED);
        assertBlocked("Ya sería todo", guard::requireLegacyOrderConfirmationBlocked,
                AiToolSafetyReason.LEGACY_ORDER_CONFIRMATION_DISABLED);
    }

    private void assertBlocked(String message, Runnable operation, AiToolSafetyReason reason) {
        guard.withinTextTurn(message, () -> {
            assertThatThrownBy(operation::run)
                    .isInstanceOfSatisfying(AiToolSafetyException.class,
                            exception -> org.assertj.core.api.Assertions.assertThat(exception.getReason()).isEqualTo(reason));
            return null;
        });
    }
}
