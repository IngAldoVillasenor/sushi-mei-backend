package com.sushimei.sushimei.backend.checkout;

/**
 * Compatibility-only formatter. Structured lines and exact snapshot totals
 * remain the operational source of truth.
 */
final class LegacyOrderDetailsFormatter {

    String format(CartSnapshot snapshot) {
        StringBuilder details = new StringBuilder("Detalle exacto de la orden:\n");
        for (CartLineSnapshot line : snapshot.items()) {
            details.append("- ")
                    .append(line.quantity())
                    .append("x ")
                    .append(line.dishName())
                    .append(" ($")
                    .append(line.unitPrice().toPlainString())
                    .append(" c/u) = $")
                    .append(line.lineTotal().toPlainString())
                    .append("\n");
        }
        return details.append("\nTOTAL A PAGAR: $")
                .append(snapshot.total().toPlainString())
                .append(" MXN")
                .toString();
    }
}
