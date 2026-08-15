package com.sushimei.sushimei.backend.agent;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Limits menu retrieval to turns for which menu context can safely help the conversational AI.
 */
@Component
public class ConversationRetrievalPolicy {

    private static final Set<String> CATALOG_TOKENS = Set.of(
            "menu", "menus", "venden", "tienen", "precio", "precios", "cuesta", "cuestan", "platillo", "platillos",
            "bebida", "bebidas", "sucursal", "catalogo");

    public boolean shouldRetrieve(String message) {
        String normalized = AiToolSafetyGuard.normalize(message);
        Set<String> tokens = AiToolSafetyGuard.tokens(normalized);

        if (normalized.isBlank() || isGreetingOrGeneralConversation(tokens) || isFinishIntent(normalized)
                || AiToolSafetyGuard.isCurrentCartQuery(normalized) || isExplicitRemoval(tokens)
                || isAmbiguousReference(tokens) || isGenericProductRequest(tokens)) {
            return false;
        }

        return containsCatalogSignal(tokens) || isProductInformationRequest(tokens) || isSpecificAddRequest(tokens);
    }

    /**
     * Identifies informational menu turns. Mutation and cart-state requests remain on the tool-capable agent.
     */
    public boolean isReadOnlyCatalogTurn(String message) {
        String normalized = AiToolSafetyGuard.normalize(message);
        Set<String> tokens = AiToolSafetyGuard.tokens(normalized);

        return shouldRetrieve(message)
                && !AiToolSafetyGuard.isCurrentCartQuery(normalized)
                && !AiToolSafetyGuard.isAddRequest(message)
                && !containsAction(tokens, Set.of(
                "quita", "quitar", "elimina", "eliminar", "cancela", "cancelar", "resta", "restar", "saca", "sacar"));
    }
    private boolean isGreetingOrGeneralConversation(Set<String> tokens) {
        return tokens.stream().anyMatch(token -> Set.of("hola", "buenas", "buenos", "gracias", "como", "estas").contains(token))
                && !containsCatalogSignal(tokens)
                && !containsAction(tokens, Set.of("quiero", "quisiera", "dame", "ponme", "agrega", "agregame", "anade", "incluye",
                "ordena", "ordenar", "ordenarme", "pido", "pedir", "falta", "falto", "faltaba"));
    }

    private boolean isFinishIntent(String normalized) {
        return AiToolSafetyGuard.isFinishOrderIntent(normalized);
    }

    private boolean isExplicitRemoval(Set<String> tokens) {
        return containsAction(tokens, Set.of("quita", "quitar", "elimina", "eliminar", "cancela", "cancelar", "resta", "restar", "saca", "sacar"))
                && hasSpecificProductToken(tokens);
    }

    private boolean isAmbiguousReference(Set<String> tokens) {
        return containsAction(tokens, Set.of("agrega", "agregame", "anade", "incluye", "dame", "damelo", "ponme"))
                && tokens.stream().anyMatch(token -> Set.of("ese", "esa", "eso", "este", "esta", "melo").contains(token));
    }

    private boolean isGenericProductRequest(Set<String> tokens) {
        return AiToolSafetyGuard.isAddRequest(String.join(" ", tokens))
                && !hasSpecificProductToken(tokens);
    }

    private boolean isSpecificAddRequest(Set<String> tokens) {
        return AiToolSafetyGuard.isAddRequest(String.join(" ", tokens))
                && hasSpecificProductToken(tokens);
    }

    private boolean isProductInformationRequest(Set<String> tokens) {
        return tokens.stream().anyMatch(Set.of("lleva", "incluye", "ingredientes", "contiene")::contains)
                && hasSpecificProductToken(tokens);
    }
    private boolean containsCatalogSignal(Set<String> tokens) {
        return CATALOG_TOKENS.stream().anyMatch(tokens::contains)
                || (tokens.contains("cuanto") && (tokens.contains("vale") || tokens.contains("sale")));
    }

    private boolean hasSpecificProductToken(Set<String> tokens) {
        return tokens.stream().anyMatch(token -> !Set.of(
                "a", "al", "con", "de", "del", "el", "la", "las", "lo", "los", "me", "mi", "para", "por", "que",
                "quiero", "quisiera", "dame", "damelo", "ponme", "pon", "agrega", "agregame", "anade", "incluye", "un",
                "ordena", "ordenar", "ordenarme", "pido", "pedir", "falta", "falto", "faltaba", "una", "unos", "unas", "y", "favor", "otro", "otra", "orden", "ordenes", "pedido", "platillo", "platillos", "producto", "productos",
                "roll", "rollo", "rollos", "bebida", "bebidas", "refresco", "refrescos", "sushi", "comida", "algo", "eso",
                "esa", "ese", "esta", "este").contains(token));
    }

    private boolean containsAction(Set<String> tokens, Set<String> actions) {
        return actions.stream().anyMatch(tokens::contains);
    }
}
