package com.cardovia.merkon.backend.security;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
class AccountDeletionPageController {

    /** Serves the sole anonymous page without a security-rechecked forward. */
    @GetMapping(value = "/account-deletion", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    ResponseEntity<Resource> page() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/account-deletion.html"));
    }
}
