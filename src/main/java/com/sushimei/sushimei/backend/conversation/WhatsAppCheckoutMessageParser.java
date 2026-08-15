package com.sushimei.sushimei.backend.conversation;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class WhatsAppCheckoutMessageParser {

    private static final Set<String> AFFIRMATIVE = Set.of(
            "si", "confirmar", "confirmo", "correcto", "adelante", "acepto");
    private static final Set<String> NEGATIVE = Set.of(
            "no", "cambiar", "cambio", "seguir", "agregar", "corregir");
    private static final Set<String> CANCEL = Set.of(
            "cancelar", "cancela", "cancelalo", "detener");
    private static final Set<String> DELIVERY = Set.of(
            "domicilio", "entrega", "delivery", "enviar", "envio");
    private static final Set<String> PICKUP = Set.of(
            "recoger", "recojo", "recoge", "pickup", "mostrador", "sucursal", "paso");
    private static final Set<String> CASH = Set.of("efectivo", "cash");
    private static final Set<String> TRANSFER = Set.of("transferencia", "transfer", "transferir");
    private static final Set<String> CARD = Set.of("tarjeta", "terminal");
    private static final Pattern MONEY = Pattern.compile("(?<!\\d)(\\d[\\d.,]*)(?!\\d)");

    private WhatsAppCheckoutMessageParser() {
    }

    static boolean isFinishOrder(String message) {
        String normalized = normalize(message);
        return normalized.contains("ya seria todo")
                || normalized.contains("eso seria todo")
                || normalized.contains("seria todo")
                || normalized.contains("ya no quiero mas")
                || normalized.contains("ya termine")
                || normalized.contains("ya acabamos")
                || normalized.contains("terminar pedido")
                || normalized.contains("finalizar pedido");
    }

    static boolean isAffirmative(String message) {
        return containsAny(message, AFFIRMATIVE);
    }

    static boolean isNegative(String message) {
        return containsAny(message, NEGATIVE);
    }

    static boolean isCancel(String message) {
        return containsAny(message, CANCEL);
    }

    static boolean isClearCart(String message) {
        Set<String> messageTokens = tokens(message);
        if (!messageTokens.contains("carrito")) {
            return false;
        }
        boolean directClear = messageTokens.stream().anyMatch(Set.of(
                "vaciar", "vacia", "vacie", "limpiar", "limpia", "borra", "borrar")::contains);
        boolean removeAll = messageTokens.stream().anyMatch(Set.of(
                "quita", "quitar", "elimina", "eliminar", "saca", "sacar")::contains)
                && messageTokens.stream().anyMatch(Set.of("todo", "todos")::contains);
        return directClear || removeAll;
    }

    static FulfillmentType fulfillment(String message) {
        Set<String> tokens = tokens(message);
        boolean delivery = tokens.stream().anyMatch(DELIVERY::contains);
        boolean pickup = tokens.stream().anyMatch(PICKUP::contains);
        if (delivery == pickup) {
            return null;
        }
        return delivery ? FulfillmentType.DELIVERY : FulfillmentType.PICKUP;
    }

    static PaymentMethod payment(String message) {
        Set<String> tokens = tokens(message);
        boolean cash = tokens.stream().anyMatch(CASH::contains);
        boolean transfer = tokens.stream().anyMatch(TRANSFER::contains);
        boolean card = tokens.stream().anyMatch(CARD::contains);
        if ((cash ? 1 : 0) + (transfer ? 1 : 0) + (card ? 1 : 0) != 1) {
            return null;
        }
        if (cash) {
            return PaymentMethod.CASH;
        }
        return transfer ? PaymentMethod.TRANSFER : PaymentMethod.CARD;
    }

    static BigDecimal cashDenomination(String message) {
        Matcher matcher = MONEY.matcher(normalize(message));
        if (!matcher.find()) {
            return null;
        }
        String amount = matcher.group(1);
        if (matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(normalizeNumber(amount));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean containsAny(String message, Set<String> candidates) {
        return tokens(message).stream().anyMatch(candidates::contains);
    }

    private static Set<String> tokens(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(normalized.split(" "))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeNumber(String amount) {
        if (amount.indexOf(',') >= 0 && amount.indexOf('.') >= 0) {
            return amount.replace(",", "");
        }
        if (amount.matches("\\d{1,3}(,\\d{3})+")) {
            return amount.replace(",", "");
        }
        return amount.replace(',', '.');
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.,]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
