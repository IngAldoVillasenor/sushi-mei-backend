package com.sushimei.sushimei.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

//@Service
public class MenuIngestionService implements CommandLineRunner {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public MenuIngestionService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Ingestando el menú de Sushi Mei...");

        ObjectMapper mapper = new ObjectMapper();
        InputStream is = getClass().getResourceAsStream("/menu_sushi_mei.json");
        List<Map<String, Object>> productos = mapper.readValue(is, new TypeReference<>() {});

        for (Map<String, Object> prod : productos) {
            String content = (String) prod.get("rag_content");
            Metadata metadata = Metadata.metadata("sku", (String) prod.get("sku"))
                    .put("categoria", (String) prod.get("categoria"))
                    .put("precio", String.valueOf(prod.get("precio")));

            // LangChain4j convierte el texto a vector y lo guarda
            Document document = Document.from(content, metadata);
            Embedding embedding = embeddingModel.embed(document.toTextSegment()).content();
            embeddingStore.add(embedding, document.toTextSegment());
        }
        System.out.println("¡Menú cargado en memoria!");
    }
}
