package com.sushimei.sushimei.backend.configuration;

import com.sushimei.sushimei.backend.agent.ConversationRetrievalPolicy;
import com.sushimei.sushimei.backend.agent.SelectiveContentRetriever;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.sql.DataSource;

@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "sushimei.features.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagConfiguration {

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(DataSource dataSource, RagProperties ragProperties) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(ragProperties.embeddingStore().table())
                .dimension(ragProperties.embeddingStore().dimension())
                .build();
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                             EmbeddingModel embeddingModel,
                                             RagProperties ragProperties,
                                             ConversationRetrievalPolicy retrievalPolicy) {
        EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder builder = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(ragProperties.retrieval().maxResults());

        if (ragProperties.retrieval().minScore() != null) {
            builder.minScore(ragProperties.retrieval().minScore());
        }

        return new SelectiveContentRetriever(builder.build(), retrievalPolicy);
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(RagProperties ragProperties) {
        return memoryId -> MessageWindowChatMemory.withMaxMessages(ragProperties.memory().maxMessages());
    }
}
