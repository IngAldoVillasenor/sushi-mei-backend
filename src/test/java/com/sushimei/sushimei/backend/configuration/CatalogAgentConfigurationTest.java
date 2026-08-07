package com.sushimei.sushimei.backend.configuration;

import com.sushimei.sushimei.backend.agent.CatalogAgent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogAgentConfigurationTest {

    @Test
    void programmaticCatalogAgentUsesRagButExposesNoToolSpecifications() {
        AtomicReference<ChatRequest> request = new AtomicReference<>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                request.set(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("Catalog response")).build();
            }
        };
        ContentRetriever contentRetriever = mock(ContentRetriever.class);
        when(contentRetriever.retrieve(any())).thenReturn(List.of(Content.from(TextSegment.from("California Roll"))));
        @SuppressWarnings("unchecked")
        ObjectProvider<ContentRetriever> contentRetrieverProvider = mock(ObjectProvider.class);
        when(contentRetrieverProvider.getIfAvailable()).thenReturn(contentRetriever);

        CatalogAgent catalogAgent = new CatalogAgentConfiguration().catalogAgent(chatModel, contentRetrieverProvider);

        assertThat(catalogAgent.chat("Que venden?")).isEqualTo("Catalog response");
        assertThat(request.get()).isNotNull();
        assertThat(request.get().toolSpecifications()).isNullOrEmpty();
        verify(contentRetriever).retrieve(any());
    }
}
