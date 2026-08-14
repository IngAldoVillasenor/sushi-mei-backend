package com.sushimei.sushimei.backend.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationRetrievalPolicyTest {

    private final ConversationRetrievalPolicy policy = new ConversationRetrievalPolicy();

    @Test
    void retrievesOnlyWhenMenuContextCanHelp() {
        assertThat(policy.shouldRetrieve("Hola")).isFalse();
        assertThat(policy.shouldRetrieve("Que venden?")).isTrue();
        assertThat(policy.shouldRetrieve("Cuanto cuesta California?")).isTrue();
        assertThat(policy.shouldRetrieve("Que lleva el California?")).isTrue();
        assertThat(policy.shouldRetrieve("Quiero un California")).isTrue();
        assertThat(policy.shouldRetrieve("Quiero dos ordenes de camarones de 4")).isTrue();
        assertThat(policy.shouldRetrieve("Una Clásica Familiar por favor")).isTrue();
        assertThat(policy.shouldRetrieve("Y una Coca de 1.75 L")).isTrue();
    }

    @Test
    void skipsRetrievalForTurnsThatShouldNotNeedUnrelatedMenuContext() {
        assertThat(policy.shouldRetrieve("Que llevo?")).isFalse();
        assertThat(policy.shouldRetrieve("Quita la Coca")).isFalse();
        assertThat(policy.shouldRetrieve("Ya seria todo")).isFalse();
        assertThat(policy.shouldRetrieve("Ponme un rollo y una bebida")).isFalse();
        assertThat(policy.shouldRetrieve("Agregamelo")).isFalse();
    }

    @Test
    void routesOnlyInformationalCatalogTurnsToTheReadOnlyAgent() {
        assertThat(policy.isReadOnlyCatalogTurn("Que venden?")).isTrue();
        assertThat(policy.isReadOnlyCatalogTurn("Cuanto cuesta California?")).isTrue();
        assertThat(policy.isReadOnlyCatalogTurn("Que lleva el California?")).isTrue();
        assertThat(policy.isReadOnlyCatalogTurn("Que llevo?")).isFalse();
        assertThat(policy.isReadOnlyCatalogTurn("Quiero un California")).isFalse();
        assertThat(policy.isReadOnlyCatalogTurn("Una Clásica Familiar por favor")).isFalse();
        assertThat(policy.isReadOnlyCatalogTurn("Y una Coca de 1.75 L")).isFalse();
        assertThat(policy.isReadOnlyCatalogTurn("Quita la Coca")).isFalse();
    }
}
