package com.sushimei.sushimei.backend.agent;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class SelectiveContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(SelectiveContentRetriever.class);

    private final ContentRetriever delegate;
    private final ConversationRetrievalPolicy retrievalPolicy;

    public SelectiveContentRetriever(ContentRetriever delegate, ConversationRetrievalPolicy retrievalPolicy) {
        this.delegate = delegate;
        this.retrievalPolicy = retrievalPolicy;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (!retrievalPolicy.shouldRetrieve(query.text())) {
            log.info("AI retrieval executed=NO resultCount=0 topScore=none");
            return List.of();
        }

        try {
            List<Content> results = delegate.retrieve(query);
            log.info("AI retrieval executed=YES resultCount={} topScore={}", results.size(), topScore(results).orElse("none"));
            return results;
        } catch (RuntimeException exception) {
            log.warn("AI retrieval executed=YES outcome=FAILURE reason={}", exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private Optional<String> topScore(List<Content> results) {
        return results.stream()
                .map(content -> content.metadata().get(ContentMetadata.SCORE))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::doubleValue)
                .max(Double::compare)
                .map(Object::toString);
    }
}
