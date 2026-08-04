package com.sushimei.sushimei.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        EmbeddingStore embeddingStore,
        Retrieval retrieval,
        Memory memory) {

    public record EmbeddingStore(String table, int dimension) {
    }

    public record Retrieval(int maxResults, Double minScore) {
    }

    public record Memory(int maxMessages) {
    }
}
