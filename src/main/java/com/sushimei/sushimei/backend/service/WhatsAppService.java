package com.sushimei.sushimei.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sushimei.sushimei.backend.configuration.StorageProperties;
import com.sushimei.sushimei.backend.configuration.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "sushimei.features.whatsapp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

    private final WhatsAppProperties whatsAppProperties;
    private final StorageProperties storageProperties;

    public WhatsAppService(WhatsAppProperties whatsAppProperties, StorageProperties storageProperties) {
        this.whatsAppProperties = whatsAppProperties;
        this.storageProperties = storageProperties;
    }

    public String downloadWhatsAppImage(String mediaId) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(whatsAppProperties.apiToken());
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String urlGraph = graphApiUrl(mediaId);
            ResponseEntity<JsonNode> responseInfo = restTemplate.exchange(urlGraph, HttpMethod.GET, entity, JsonNode.class);
            String imageUrl = responseInfo.getBody().path("url").asText();

            ResponseEntity<byte[]> responseMedia = restTemplate.exchange(imageUrl, HttpMethod.GET, entity, byte[].class);
            byte[] imageBytes = responseMedia.getBody();

            Files.createDirectories(storageProperties.receiptsDirectory());
            Path path = storageProperties.receiptsDirectory().resolve(mediaId + ".jpg");
            Files.write(path, imageBytes);

            log.info("WhatsApp receipt downloaded to {}", path);
            return path.toString();
        } catch (Exception e) {
            log.warn("Unable to download WhatsApp receipt", e);
            return null;
        }
    }

    public boolean sendMessage(String toPhoneNumber, String message) {
        RestTemplate restTemplate = new RestTemplate();
        String url = graphApiUrl(whatsAppProperties.phoneNumberId() + "/messages");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(whatsAppProperties.apiToken());

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", toPhoneNumber);
        body.put("type", "text");

        Map<String, String> textNode = new HashMap<>();
        textNode.put("preview_url", "false");
        textNode.put("body", message);
        body.put("text", textNode);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForEntity(url, request, String.class);
            log.info("WhatsApp response sent to {}", toPhoneNumber);
            return true;
        } catch (Exception e) {
            log.warn("Unable to send WhatsApp response to {}", toPhoneNumber, e);
            return false;
        }
    }

    private String graphApiUrl(String resource) {
        return "https://graph.facebook.com/" + whatsAppProperties.graphApiVersion() + "/" + resource;
    }
}
