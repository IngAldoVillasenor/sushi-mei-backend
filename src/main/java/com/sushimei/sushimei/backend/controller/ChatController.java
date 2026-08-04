package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.agent.SushiAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sushi")
public class ChatController {

    private final SushiAgent sushiAgent;

    // Inyección de dependencias por constructor (Best practice)
    public ChatController(SushiAgent sushiAgent) {
        this.sushiAgent = sushiAgent;
    }

    @GetMapping("/chat")
    public String chat(
            @RequestParam String telefono,
            @RequestParam String mensaje) {

        return sushiAgent.chat(telefono, telefono, mensaje);
    }
}