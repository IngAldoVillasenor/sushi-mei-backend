package com.sushimei.sushimei.backend.configuration;

import com.sushimei.sushimei.backend.agent.CatalogAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit wiring keeps catalog turns separate from Spring's automatic @Tool discovery.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sushimei.features.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CatalogAgentConfiguration {

    @Bean
    public CatalogAgent catalogAgent(ChatModel chatModel, ObjectProvider<ContentRetriever> contentRetrieverProvider) {
        AiServices<CatalogAgent> builder = AiServices.builder(CatalogAgent.class)
                .chatModel(chatModel);

        ContentRetriever contentRetriever = contentRetrieverProvider.getIfAvailable();
        if (contentRetriever != null) {
            builder.contentRetriever(contentRetriever);
        }

        return builder.build();
    }
}
