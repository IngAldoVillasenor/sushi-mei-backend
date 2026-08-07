package com.sushimei.sushimei.backend.agent;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SelectiveContentRetrieverTest {

    @Test
    void skipsEmbeddingRetrievalForGreetingWithoutCallingTheDelegate() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        SelectiveContentRetriever retriever = new SelectiveContentRetriever(delegate, new ConversationRetrievalPolicy());

        assertThat(retriever.retrieve(Query.from("Hola"))).isEmpty();

        verifyNoInteractions(delegate);
    }

    @Test
    void delegatesMenuLookupAndReturnsTheRetrievedContentUnchanged() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        Query query = Query.from("¿Qué venden?");
        List<Content> expected = List.of(Content.from(TextSegment.from("California Roll"),
                Map.of(ContentMetadata.SCORE, 0.91d)));
        when(delegate.retrieve(query)).thenReturn(expected);
        SelectiveContentRetriever retriever = new SelectiveContentRetriever(delegate, new ConversationRetrievalPolicy());

        assertThat(retriever.retrieve(query)).isSameAs(expected);

        verify(delegate).retrieve(query);
    }
}
