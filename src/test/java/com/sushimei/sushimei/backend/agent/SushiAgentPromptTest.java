package com.sushimei.sushimei.backend.agent;

import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SushiAgentPromptTest {

    @Test
    void systemPromptKeepsConversationGroundedWithoutUsingChatMemoryAsCheckoutState() throws NoSuchMethodException {
        SystemMessage systemMessage = SushiAgent.class
                .getMethod("chat", String.class, String.class, String.class)
                .getAnnotation(SystemMessage.class);
        String prompt = String.join("\n", Arrays.asList(systemMessage.value()));

        assertThat(prompt)
                .contains("Sushi Mei", "menú recuperado", "No inventes platillos, precios", "{{telefono}}")
                .contains("varios productos identificables", "Consulta el carrito solo cuando", "No elijas productos para categorías genéricas", "No resuelvas pronombres")
                .contains("historial sirve solo para continuidad conversacional")
                .contains("No uses confirmOrder")
                .doesNotContain("REGLA ANTI-BUCLES", "MÁRCALO COMO COMPLETADO EN TU MENTE", "Paso 1:", "CLABE:",
                        "SUGERENCIAS (UP-SELLING)", "CIERRE DE TURNO");
    }
}
