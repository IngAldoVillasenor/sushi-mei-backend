package com.sushimei.sushimei.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Service
public class WhatsAppService {

    // Estas variables las pondremos en el application.yml
    @Value("${whatsapp.api.token}")
    private String apiToken;

    @Value("${whatsapp.api.phone-number-id}")
    private String phoneNumberId;

    // Carpeta donde se guardarán los tickets (Asegúrate de crear esta carpeta en tu PC)
    private final String UPLOAD_DIR = "C:/sushimei/comprobantes/";

    public String downloadWhatsAppImage(String mediaId) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // PASO 1: Consultar la URL de la imagen usando el mediaId
            String urlGraph = "https://graph.facebook.com/v19.0/" + mediaId;
            ResponseEntity<JsonNode> responseInfo = restTemplate.exchange(urlGraph, HttpMethod.GET, entity, JsonNode.class);

            String imageUrl = responseInfo.getBody().path("url").asText();

            // PASO 2: Descargar los bytes de la imagen
            ResponseEntity<byte[]> responseMedia = restTemplate.exchange(imageUrl, HttpMethod.GET, entity, byte[].class);
            byte[] imageBytes = responseMedia.getBody();

            // PASO 3: Guardar la imagen en el disco duro
            Files.createDirectories(Paths.get(UPLOAD_DIR)); // Crea la carpeta si no existe
            String filePath = UPLOAD_DIR + mediaId + ".jpg";

            Path path = Paths.get(filePath);
            Files.write(path, imageBytes);

            System.out.println("📸 ¡Comprobante descargado exitosamente en: " + filePath + "!");
            return filePath;

        } catch (Exception e) {
            System.err.println("⚠️ Error descargando la imagen: " + e.getMessage());
            return null;
        }
    }

    public void sendMessage(String toPhoneNumber, String message) {
        RestTemplate restTemplate = new RestTemplate();
        String url = "https://graph.facebook.com/v19.0/" + phoneNumberId + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiToken);

        // Construimos el JSON exacto que pide Meta para enviar un mensaje de texto
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
            System.out.println("📤 Respuesta enviada a WhatsApp: " + toPhoneNumber);
        } catch (Exception e) {
            System.err.println("❌ Error enviando mensaje a WhatsApp: " + e.getMessage());
        }
    }
}