package com.sushimei.sushimei.backend.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Read-only menu and product-information assistant. It is intentionally stateless and has no tools.
 */
public interface CatalogAgent {

    @SystemMessage({
            "Eres el asistente de catalogo de Sushi Mei. Responde en espanol calido, claro y conciso.",
            "Responde solo preguntas de menu, productos, ingredientes, disponibilidad y precios usando el contexto recuperado.",
            "No inventes productos, precios ni disponibilidad. Si el contexto no permite responder, dilo con claridad.",
            "No tienes herramientas y no puedes agregar, quitar ni consultar el carrito.",
            "Nunca afirmes que modificaste un carrito, creaste una orden, procesaste un pago o confirmaste un pedido."
    })
    String chat(@UserMessage String message);
}
