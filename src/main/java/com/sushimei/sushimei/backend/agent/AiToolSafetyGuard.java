package com.sushimei.sushimei.backend.agent;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Per-turn guard for AI-initiated cart tools. It is intentionally not a checkout state machine.
 */
@Component
public class AiToolSafetyGuard {

    private static final Set<String> ADD_ACTIONS = Set.of(
            "quiero", "quisiera", "dame", "damelo", "ponme", "pon", "agrega", "agregame", "anade", "incluye",
            "ordena", "ordenar", "ordenarme", "pido", "pedir", "falta", "falto", "faltaba");
    private static final Set<String> REMOVE_ACTIONS = Set.of(
            "quita", "quitar", "elimina", "eliminar", "cancela", "cancelar", "resta", "restar", "saca", "sacar");
    private static final Set<String> SIMPLE_GREETING_TOKENS = Set.of(
            "hola", "buenas", "buenos", "dias", "tardes", "noches");
    private static final Set<String> AMBIGUOUS_REFERENCE_TOKENS = Set.of(
            "ese", "esa", "esto", "esta", "melo", "lo", "la");
    private static final Set<String> IMPLICIT_ADD_MARKERS = Set.of(
            "un", "una", "unos", "unas", "por", "favor", "y", "tambien", "otro", "otra");
    private static final Set<String> INFORMATION_REQUEST_TOKENS = Set.of(
            "que", "cual", "cuales", "cuanto", "cuantos", "cuesta", "cuestan", "precio", "precios", "tienen",
            "venden", "lleva", "incluye", "ingredientes", "contiene");
    private static final Set<String> AMBIGUOUS_PRODUCT_MODIFIERS = Set.of(
            "charola", "familiar", "supreme", "combo", "paquete", "box");
    private static final Set<String> SINGULAR_QUANTITY_TOKENS = Set.of("un", "una");
    private static final Map<String, Integer> QUANTITY_WORDS = Map.of(
            "uno", 1, "dos", 2, "tres", 3, "cuatro", 4, "cinco", 5,
            "seis", 6, "siete", 7, "ocho", 8, "nueve", 9, "diez", 10);
    private static final Set<String> NON_PRODUCT_TOKENS = Set.of(
            "a", "al", "con", "de", "del", "el", "ella", "ellos", "esa", "ese", "esto", "la", "las", "lo", "los",
            "me", "mi", "para", "por", "que", "quiero", "quisiera", "dame", "damelo", "ponme", "pon", "agrega",
            "agregame", "anade", "incluye", "quita", "quitar", "elimina", "eliminar", "cancela", "cancelar", "resta",
            "ordena", "ordenar", "ordenarme", "pido", "pedir", "falta", "falto", "faltaba", "restar", "saca", "sacar", "tambien", "un", "una",
            "unos", "unas", "y", "favor", "otro", "otra", "orden", "ordenes", "pedido",
            "platillo", "platillos", "producto", "productos", "roll", "rollo", "rollos", "bebida", "bebidas", "refresco",
            "refrescos", "japonesa", "sushi", "comida", "algo", "eso", "esta", "este");

    private final ThreadLocal<TurnContext> activeTurn = new ThreadLocal<>();

    public <T> T withinTextTurn(String message, Supplier<T> operation) {
        return executeTextTurn(message, operation).value();
    }

    public <T> AiToolTurnResult<T> executeTextTurn(String message, Supplier<T> operation) {
        TurnContext previous = activeTurn.get();
        TurnContext current = new TurnContext(normalize(message));
        activeTurn.set(current);
        try {
            return new AiToolTurnResult<>(operation.get(), current.mutationOutcome(), current.authoritativeToolResponse(),
                    current.successfulAddCount());
        } finally {
            if (previous == null) {
                activeTurn.remove();
            } else {
                activeTurn.set(previous);
            }
        }
    }

    public void requireAddAllowed(String dishName) {
        requireAddAllowed(dishName, 1);
    }

    public void requireAddAllowed(String dishName, int quantity) {
        TurnContext context = activeTurn.get();
        if (context == null) {
            return;
        }
        if (!isAddRequest(context.normalizedMessage())
                || !hasExplicitDishReference(context.normalizedMessage(), dishName)
                || !hasExplicitQuantity(context.normalizedMessage(), dishName, quantity)) {
            throw new AiToolSafetyException(AiToolSafetyReason.ADD_NOT_EXPLICITLY_REQUESTED);
        }
    }

    public void requireRemoveAllowed(String dishName) {
        TurnContext context = activeTurn.get();
        if (context == null) {
            return;
        }
        if (!containsAnyToken(context.messageTokens(), REMOVE_ACTIONS)
                || !hasExplicitDishReference(context.normalizedMessage(), dishName)) {
            throw new AiToolSafetyException(AiToolSafetyReason.REMOVE_NOT_EXPLICITLY_REQUESTED);
        }
    }

    public void requireCartQueryAllowed() {
        TurnContext context = activeTurn.get();
        if (context == null) {
            return;
        }
        if (!isCurrentCartQuery(context.normalizedMessage())) {
            throw new AiToolSafetyException(AiToolSafetyReason.CART_QUERY_NOT_REQUESTED);
        }
        if (context.cartQueryPerformed()) {
            throw new AiToolSafetyException(AiToolSafetyReason.CART_QUERY_ALREADY_PERFORMED);
        }
        context.markCartQueryPerformed();
    }

    public void requireLegacyOrderConfirmationBlocked() {
        if (activeTurn.get() != null) {
            throw new AiToolSafetyException(AiToolSafetyReason.LEGACY_ORDER_CONFIRMATION_DISABLED);
        }
    }

    public void recordAddSucceeded(String dishName, int quantity, String cartContents) {
        recordSuccessfulMutation(AiMutationTurnOutcome.ADD_SUCCEEDED, dishName, quantity, cartContents);
    }

    public void recordRemoveSucceeded(String dishName, int quantity, String cartContents) {
        recordSuccessfulMutation(AiMutationTurnOutcome.REMOVE_SUCCEEDED, dishName, quantity, cartContents);
    }

    public void recordCartQuerySucceeded(String response) {
        recordAuthoritativeToolResponse(AiMutationTurnOutcome.CART_QUERY_SUCCEEDED, response);
    }

    public void recordAddBlocked() {
        recordFailureOrBlockedOutcome(AiMutationTurnOutcome.ADD_BLOCKED);
    }

    public void recordRemoveBlocked() {
        recordFailureOrBlockedOutcome(AiMutationTurnOutcome.REMOVE_BLOCKED);
    }

    public void recordAddFailed() {
        recordFailureOrBlockedOutcome(AiMutationTurnOutcome.ADD_FAILED);
    }

    public void recordRemoveFailed() {
        recordFailureOrBlockedOutcome(AiMutationTurnOutcome.REMOVE_FAILED);
    }

    public void recordConfirmationBlocked() {
        recordFailureOrBlockedOutcome(AiMutationTurnOutcome.CONFIRMATION_BLOCKED);
    }

    static boolean isFinishOrderIntent(String message) {
        String normalized = normalize(message);
        return normalized.contains("ya seria todo")
                || normalized.contains("eso seria todo")
                || normalized.contains("seria todo")
                || normalized.contains("ya no quiero mas")
                || normalized.contains("ya termine")
                || normalized.contains("ya acabamos");
    }

    static boolean isAddRequest(String message) {
        String normalized = normalize(message);
        Set<String> messageTokens = tokens(normalized);
        return containsAnyToken(messageTokens, ADD_ACTIONS) || isImplicitAddSelection(messageTokens);
    }

    static boolean mentionsAmbiguousCalpi(String message) {
        Set<String> messageTokens = tokens(message);
        return messageTokens.contains("calpi")
                && messageTokens.stream().noneMatch(Set.of(
                "fresa", "mango", "mineral", "natural", "500", "500ml")::contains);
    }

    static boolean isStandaloneAmbiguousCalpiAdd(String message) {
        Set<String> messageTokens = tokens(message);
        return isAddRequest(message) && mentionsAmbiguousCalpi(message) && !messageTokens.contains("y");
    }

    static int requestedItemCountLowerBound(String message) {
        if (!isAddRequest(message)) {
            return 0;
        }
        String normalized = normalize(message);
        int itemCount = 1;
        int fromIndex = 0;
        while ((fromIndex = normalized.indexOf(" y ", fromIndex)) >= 0) {
            itemCount++;
            fromIndex += 3;
        }
        return Math.max(itemCount, explicitItemMarkerCount(normalized));
    }

    private static boolean isImplicitAddSelection(Set<String> messageTokens) {
        return !messageTokens.isEmpty()
                && containsAnyToken(messageTokens, IMPLICIT_ADD_MARKERS)
                && !containsAnyToken(messageTokens, INFORMATION_REQUEST_TOKENS)
                && hasPotentialProductToken(messageTokens);
    }

    static boolean isSimpleGreeting(String message) {
        Set<String> tokens = tokens(message);
        return !tokens.isEmpty() && SIMPLE_GREETING_TOKENS.containsAll(tokens);
    }

    static boolean isAmbiguousAddPronounRequest(String message) {
        Set<String> tokens = tokens(message);
        return tokens.stream().anyMatch(Set.of("agregamelo", "ponmelo", "damelo")::contains)
                || (containsAnyToken(tokens, ADD_ACTIONS) && containsAmbiguousReference(tokens)
                && !hasPotentialProductToken(tokens));
    }

    static boolean isAmbiguousRemovePronounRequest(String message) {
        Set<String> tokens = tokens(message);
        return tokens.stream().anyMatch(Set.of("quitalo", "quitala", "eliminalo", "sacalo")::contains)
                || (containsAnyToken(tokens, REMOVE_ACTIONS) && containsAmbiguousReference(tokens)
                && !hasPotentialProductToken(tokens));
    }

    static boolean isStandaloneAmbiguousReference(String message) {
        Set<String> tokens = tokens(message);
        return !tokens.isEmpty()
                && tokens.stream().allMatch(token -> Set.of("ese", "esa", "esto", "esta", "por", "favor").contains(token));
    }

    private static boolean hasPotentialProductToken(Set<String> messageTokens) {
        return messageTokens.stream().anyMatch(token -> !NON_PRODUCT_TOKENS.contains(token)
                && token.length() >= 3
                && !token.chars().allMatch(Character::isDigit));
    }

    static boolean hasExplicitDishReference(String message, String dishName) {
        return !matchingReferenceStarts(message, dishName).isEmpty();
    }

    static boolean hasExplicitQuantity(String message, String dishName, int quantity) {
        if (quantity <= 0) {
            return false;
        }
        String[] messageTokens = normalize(message).split(" ");
        return matchingReferenceStarts(message, dishName).stream()
                .anyMatch(start -> requestedQuantityBefore(messageTokens, start) == quantity);
    }

    private static List<Integer> matchingReferenceStarts(String message, String dishName) {
        List<PositionedToken> messageTokens = positionedProductTokens(message);
        List<String> identityTokens = productTokens(dishName);
        if (identityTokens.isEmpty() || messageTokens.size() < identityTokens.size()) {
            return List.of();
        }

        List<Integer> starts = new ArrayList<>();
        for (int start = 0; start <= messageTokens.size() - identityTokens.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < identityTokens.size(); offset++) {
                if (!messageTokens.get(start + offset).value().equals(identityTokens.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                starts.add(messageTokens.get(start).sourceIndex());
            }
        }
        return List.copyOf(starts);
    }

    private static List<PositionedToken> positionedProductTokens(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] sourceTokens = normalized.split(" ");
        List<PositionedToken> result = new ArrayList<>();
        for (int index = 0; index < sourceTokens.length; index++) {
            if (isProductIdentityToken(sourceTokens[index])) {
                result.add(new PositionedToken(sourceTokens[index], index));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> productTokens(String value) {
        return positionedProductTokens(value).stream().map(PositionedToken::value).toList();
    }

    private static boolean isProductIdentityToken(String token) {
        return !NON_PRODUCT_TOKENS.contains(token)
                && !AMBIGUOUS_PRODUCT_MODIFIERS.contains(token)
                && (token.length() >= 3 || token.chars().allMatch(Character::isDigit));
    }

    private static int requestedQuantityBefore(String[] sourceTokens, int productStart) {
        for (int index = productStart - 1; index >= 0 && productStart - index <= 3; index--) {
            String token = sourceTokens[index];
            if ("y".equals(token)) {
                break;
            }
            Integer quantity = explicitQuantity(token);
            if (quantity != null) {
                return quantity;
            }
            if (!NON_PRODUCT_TOKENS.contains(token) && !ADD_ACTIONS.contains(token)) {
                break;
            }
        }
        return 1;
    }

    private static int explicitItemMarkerCount(String normalizedMessage) {
        if (normalizedMessage.isBlank()) {
            return 0;
        }
        String[] sourceTokens = normalizedMessage.split(" ");
        int markers = 0;
        for (int index = 0; index < sourceTokens.length; index++) {
            String token = sourceTokens[index];
            if (SINGULAR_QUANTITY_TOKENS.contains(token) && hasProductTokenAfter(sourceTokens, index + 1)) {
                markers++;
                continue;
            }
            if (isQuantityToken(token)
                    && (index == 0 || !SINGULAR_QUANTITY_TOKENS.contains(sourceTokens[index - 1]))
                    && hasProductTokenImmediatelyAfter(sourceTokens, index + 1)) {
                markers++;
            }
        }
        return markers;
    }

    private static boolean hasProductTokenAfter(String[] sourceTokens, int fromIndex) {
        for (int index = fromIndex; index < sourceTokens.length && index - fromIndex <= 2; index++) {
            if ("y".equals(sourceTokens[index])) {
                return false;
            }
            if (isProductIdentityToken(sourceTokens[index])) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProductTokenImmediatelyAfter(String[] sourceTokens, int index) {
        return index < sourceTokens.length
                && !sourceTokens[index].chars().allMatch(Character::isDigit)
                && isProductIdentityToken(sourceTokens[index]);
    }

    private static boolean isQuantityToken(String token) {
        return explicitQuantity(token) != null && !SINGULAR_QUANTITY_TOKENS.contains(token);
    }

    private static Integer explicitQuantity(String token) {
        if (SINGULAR_QUANTITY_TOKENS.contains(token)) {
            return 1;
        }
        Integer wordQuantity = QUANTITY_WORDS.get(token);
        if (wordQuantity != null) {
            return wordQuantity;
        }
        if (!token.isBlank() && token.chars().allMatch(Character::isDigit)) {
            try {
                int parsed = Integer.parseInt(token);
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Set<String> dishTokens(String value) {
        Set<String> tokens = new HashSet<>(tokens(value));
        tokens.removeAll(NON_PRODUCT_TOKENS);
        tokens.removeIf(token -> token.length() < 3 && !token.chars().allMatch(Character::isDigit));
        return tokens;
    }

    private record PositionedToken(String value, int sourceIndex) {
    }

    static Set<String> tokens(String value) {
        if (value == null) {
            return Set.of();
        }
        return Arrays.stream(normalize(value).split(" "))
                .filter(token -> !token.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    static boolean isCurrentCartQuery(String normalizedMessage) {
        return normalizedMessage.contains("que llevo")
                || normalizedMessage.contains("que tengo")
                || normalizedMessage.contains("cuanto es")
                || normalizedMessage.contains("cuanto llevo")
                || normalizedMessage.contains("mi carrito")
                || normalizedMessage.contains("el carrito")
                || normalizedMessage.contains("mi pedido")
                || normalizedMessage.contains("mi orden")
                || tokens(normalizedMessage).contains("total");
    }

    private static boolean containsAnyToken(Set<String> messageTokens, Set<String> expectedTokens) {
        return expectedTokens.stream().anyMatch(messageTokens::contains);
    }

    private static boolean containsAmbiguousReference(Set<String> messageTokens) {
        return messageTokens.stream().anyMatch(AMBIGUOUS_REFERENCE_TOKENS::contains);
    }

    private void recordAuthoritativeToolResponse(AiMutationTurnOutcome outcome, String response) {
        TurnContext context = activeTurn.get();
        if (context == null || response == null || response.isBlank()) {
            return;
        }
        context.setAuthoritativeToolResponse(response);
        if (context.mutationOutcome() == AiMutationTurnOutcome.NONE || context.mutationOutcome().isSuccessfulCartOperation()) {
            context.setMutationOutcome(outcome);
        }
    }

    private void recordSuccessfulMutation(AiMutationTurnOutcome outcome,
                                          String dishName,
                                          int quantity,
                                          String cartContents) {
        TurnContext context = activeTurn.get();
        if (context == null || dishName == null || dishName.isBlank()) {
            return;
        }
        context.addSuccessfulMutation(outcome, quantity + " x " + dishName, cartContents);
        if (outcome == AiMutationTurnOutcome.ADD_SUCCEEDED) {
            context.incrementSuccessfulAddCount();
        }
        if (context.mutationOutcome() == AiMutationTurnOutcome.NONE || context.mutationOutcome().isSuccessfulCartOperation()) {
            context.setMutationOutcome(outcome);
        }
    }

    private void recordFailureOrBlockedOutcome(AiMutationTurnOutcome outcome) {
        TurnContext context = activeTurn.get();
        if (context != null && (context.mutationOutcome() == AiMutationTurnOutcome.NONE
                || context.mutationOutcome().isSuccessfulCartOperation())) {
            context.setMutationOutcome(outcome);
        }
    }

    private static final class TurnContext {
        private final String normalizedMessage;
        private final Set<String> messageTokens;
        private boolean cartQueryPerformed;
        private AiMutationTurnOutcome mutationOutcome = AiMutationTurnOutcome.NONE;
        private String authoritativeToolResponse;
        private final List<SuccessfulMutation> successfulMutations = new ArrayList<>();
        private String latestCartContents;
        private int successfulAddCount;

        private TurnContext(String normalizedMessage) {
            this.normalizedMessage = normalizedMessage;
            this.messageTokens = tokens(normalizedMessage);
        }

        private String normalizedMessage() {
            return normalizedMessage;
        }

        private Set<String> messageTokens() {
            return messageTokens;
        }

        private boolean cartQueryPerformed() {
            return cartQueryPerformed;
        }

        private void markCartQueryPerformed() {
            cartQueryPerformed = true;
        }

        private AiMutationTurnOutcome mutationOutcome() {
            return mutationOutcome;
        }

        private void setMutationOutcome(AiMutationTurnOutcome mutationOutcome) {
            this.mutationOutcome = mutationOutcome;
        }

        private String authoritativeToolResponse() {
            if (!successfulMutations.isEmpty()) {
                return consolidatedMutationResponse();
            }
            return authoritativeToolResponse;
        }

        private int successfulAddCount() {
            return successfulAddCount;
        }

        private void incrementSuccessfulAddCount() {
            successfulAddCount++;
        }

        private void setAuthoritativeToolResponse(String response) {
            authoritativeToolResponse = response;
        }

        private void addSuccessfulMutation(AiMutationTurnOutcome outcome, String itemDescription, String cartContents) {
            successfulMutations.add(new SuccessfulMutation(outcome, itemDescription));
            latestCartContents = cartContents;
        }

        private String consolidatedMutationResponse() {
            List<String> additions = successfulMutations.stream()
                    .filter(mutation -> mutation.outcome() == AiMutationTurnOutcome.ADD_SUCCEEDED)
                    .map(SuccessfulMutation::itemDescription)
                    .toList();
            List<String> removals = successfulMutations.stream()
                    .filter(mutation -> mutation.outcome() == AiMutationTurnOutcome.REMOVE_SUCCEEDED)
                    .map(SuccessfulMutation::itemDescription)
                    .toList();
            List<String> clauses = new ArrayList<>();
            if (!additions.isEmpty()) {
                clauses.add("agregué " + joinInSpanish(additions) + " a tu carrito");
            }
            if (!removals.isEmpty()) {
                clauses.add("quité " + joinInSpanish(removals) + " de tu carrito");
            }
            String summary = joinInSpanish(clauses);
            String response = "¡Listo! " + Character.toUpperCase(summary.charAt(0)) + summary.substring(1) + ".";
            return latestCartContents == null || latestCartContents.isBlank()
                    ? response
                    : response + "\n" + latestCartContents;
        }

        private static String joinInSpanish(List<String> values) {
            if (values.size() == 1) {
                return values.get(0);
            }
            return String.join(", ", values.subList(0, values.size() - 1))
                    + " y " + values.get(values.size() - 1);
        }
    }

    private record SuccessfulMutation(AiMutationTurnOutcome outcome, String itemDescription) {
    }
}
