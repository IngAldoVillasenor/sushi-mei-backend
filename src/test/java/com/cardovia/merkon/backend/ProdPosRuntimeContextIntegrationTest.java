package com.cardovia.merkon.backend;

import com.cardovia.merkon.backend.agent.AiConversationService;
import com.cardovia.merkon.backend.checkout.OrderService;
import com.cardovia.merkon.backend.configuration.WebConfig;
import com.cardovia.merkon.backend.controller.ChatController;
import com.cardovia.merkon.backend.controller.WhatsAppWebhookController;
import com.cardovia.merkon.backend.order.OrderLifecycleService;
import com.cardovia.merkon.backend.pos.ManualPosOrderService;
import com.cardovia.merkon.backend.security.SecurityTestKeyConfiguration;
import com.cardovia.merkon.backend.service.WhatsAppService;
import com.cardovia.merkon.backend.whatsapp.InboundMessageIdempotencyService;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import javax.sql.DataSource;
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

    @Autowired
    private DataSource dataSource;

    @Test
    void prodPosStartsWithoutAiOrWhatsAppDependenciesWhileKeepingOperationalBeansAvailable() {
        assertThat(environment.getProperty("server.port")).isEqualTo("18080");
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");

        assertThat(applicationContext.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(EmbeddingModel.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AiConversationService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WhatsAppService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WhatsAppWebhookController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChatController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(InboundMessageIdempotencyService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OrderService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WebConfig.class)).isEmpty();
        assertThat(environment.getProperty("storage.receipts-directory")).isEmpty();
        assertThat(environment.getProperty("storage.public-upload-directory")).isEmpty();

        assertThat(applicationContext.getBean(ManualPosOrderService.class)).isNotNull();
        assertThat(applicationContext.getBean(OrderLifecycleService.class)).isNotNull();
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain.class)).isNotEmpty();
    }

    @Test
    void prodPosUsesTheSharedTestDatasourceWithAConservativeHikariPool() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);

        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getJdbcUrl()).startsWith("jdbc:h2:mem:merkon-");
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(5);
        assertThat(hikari.getMinimumIdle()).isEqualTo(1);
    }
}
