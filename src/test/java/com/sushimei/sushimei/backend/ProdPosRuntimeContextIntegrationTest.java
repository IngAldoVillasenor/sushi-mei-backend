package com.sushimei.sushimei.backend;

import com.sushimei.sushimei.backend.agent.AiConversationService;
import com.sushimei.sushimei.backend.checkout.OrderService;
import com.sushimei.sushimei.backend.controller.ChatController;
import com.sushimei.sushimei.backend.controller.WhatsAppWebhookController;
import com.sushimei.sushimei.backend.order.OrderLifecycleService;
import com.sushimei.sushimei.backend.pos.ManualPosOrderService;
import com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration;
import com.sushimei.sushimei.backend.service.WhatsAppService;
import com.sushimei.sushimei.backend.whatsapp.InboundMessageIdempotencyService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "PORT=18080")
@ActiveProfiles({"test", "prod-pos"})
@Import(SecurityTestKeyConfiguration.class)
class ProdPosRuntimeContextIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Test
    void prodPosStartsWithoutAiOrWhatsAppDependenciesWhileKeepingOperationalBeansAvailable() {
        assertThat(environment.getProperty("server.port")).isEqualTo("18080");

        assertThat(applicationContext.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(EmbeddingModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AiConversationService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WhatsAppService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WhatsAppWebhookController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChatController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(InboundMessageIdempotencyService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OrderService.class)).isEmpty();

        assertThat(applicationContext.getBean(ManualPosOrderService.class)).isNotNull();
        assertThat(applicationContext.getBean(OrderLifecycleService.class)).isNotNull();
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain.class)).isNotEmpty();
    }
}
