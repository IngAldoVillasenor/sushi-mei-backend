package com.cardovia.merkon.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password-recovery")
public class PasswordRecoveryController {

    private final PasswordRecoveryService recovery;
    private final RegistrationClientAddressResolver clientAddresses;

    PasswordRecoveryController(PasswordRecoveryService recovery,
                               RegistrationClientAddressResolver clientAddresses) {
        this.recovery = recovery;
        this.clientAddresses = clientAddresses;
    }

    @PostMapping("/request")
    public ResponseEntity<PasswordRecoveryResponse> request(@Valid @RequestBody PasswordRecoveryRequest request,
                                                             HttpServletRequest servletRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(recovery.request(request, clientAddresses.resolve(servletRequest)));
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody PasswordRecoveryConfirmRequest request,
                                        HttpServletRequest servletRequest) {
        recovery.confirm(request, clientAddresses.resolve(servletRequest));
        return ResponseEntity.noContent().build();
    }
}
