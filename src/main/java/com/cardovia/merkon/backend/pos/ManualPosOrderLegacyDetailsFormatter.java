package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.promotion.PromotionQuoteLineResponse;
import com.cardovia.merkon.backend.promotion.PromotionQuoteResponse;

/** Legacy presentation text only; immutable structured lines remain operational truth. */
final class ManualPosOrderLegacyDetailsFormatter {
    private ManualPosOrderLegacyDetailsFormatter() { }
    static String format(PromotionQuoteResponse quote) {
        StringBuilder result = new StringBuilder("Orden POS\n");
        for (PromotionQuoteLineResponse line : quote.lines()) {
            result.append("- ").append(line.quantity()).append(" x ").append(line.name()).append("\n");
            line.rewards().forEach(reward -> result.append("- Promoción: ").append(reward.name()).append("\n"));
        }
        return result.append("TOTAL: ").append(quote.total().toPlainString()).toString();
    }
}
