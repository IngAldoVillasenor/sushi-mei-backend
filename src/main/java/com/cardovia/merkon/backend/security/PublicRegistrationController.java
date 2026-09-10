package com.cardovia.merkon.backend.security;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/registration")
public class PublicRegistrationController {

    private final PublicRegistrationService registrations;
    private final RegistrationClientAddressResolver clientAddresses;

    public PublicRegistrationController(PublicRegistrationService registrations,
                                        RegistrationClientAddressResolver clientAddresses) {
        this.registrations = registrations;
        this.clientAddresses = clientAddresses;
    }

    @PostMapping
    public ResponseEntity<PublicRegistrationResponse> register(@Valid @RequestBody PublicRegistrationRequest request,
                                                                jakarta.servlet.http.HttpServletRequest servletRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(registrations.register(request, clientAddresses.resolve(servletRequest)));
    }
}
