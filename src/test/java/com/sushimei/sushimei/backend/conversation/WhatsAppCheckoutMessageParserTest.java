package com.sushimei.sushimei.backend.conversation;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppCheckoutMessageParserTest {

    @Test
    void recognizesAccentedCheckoutChoicesWithoutUsingTheModel() {
        assertThat(WhatsAppCheckoutMessageParser.isFinishOrder("Sí, ya sería todo")).isTrue();
        assertThat(WhatsAppCheckoutMessageParser.isAffirmative("sí, confirmar")).isTrue();
        assertThat(WhatsAppCheckoutMessageParser.fulfillment("entrega a domicilio"))
                .isEqualTo(FulfillmentType.DELIVERY);
        assertThat(WhatsAppCheckoutMessageParser.fulfillment("paso a recoger"))
                .isEqualTo(FulfillmentType.PICKUP);
        assertThat(WhatsAppCheckoutMessageParser.payment("pagaré por transferencia"))
                .isEqualTo(PaymentMethod.TRANSFER);
    }

    @Test
    void parsesMexicanCashAmountsAndRejectsAmbiguousInput() {
        assertThat(WhatsAppCheckoutMessageParser.cashDenomination("pago con 500"))
                .isEqualByComparingTo(new BigDecimal("500"));
        assertThat(WhatsAppCheckoutMessageParser.cashDenomination("pago con $1,000"))
                .isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(WhatsAppCheckoutMessageParser.cashDenomination("entre 200 y 500"))
                .isNull();
        assertThat(WhatsAppCheckoutMessageParser.payment("efectivo o transferencia"))
                .isNull();
    }
}
