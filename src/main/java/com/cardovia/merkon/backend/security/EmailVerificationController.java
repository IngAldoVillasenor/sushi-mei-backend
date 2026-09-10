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
@RequestMapping("/api/v1/registration/email-verification")
public class EmailVerificationController {

    private final EmailVerificationService verification;
    private final RegistrationClientAddressResolver clientAddresses;

    EmailVerificationController(EmailVerificationService verification,
                                RegistrationClientAddressResolver clientAddresses) {
        this.verification = verification;
        this.clientAddresses = clientAddresses;
    }

    @PostMapping("/verify")
    public EmailVerificationResponse verify(@Valid @RequestBody EmailVerificationRequest request,
                                            HttpServletRequest servletRequest) {
        return verification.verify(request, clientAddresses.resolve(servletRequest));
    }

    @PostMapping("/resend")
    public ResponseEntity<EmailVerificationResponse> resend(@Valid @RequestBody EmailVerificationResendRequest request,
                                                             HttpServletRequest servletRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(verification.resend(request, clientAddresses.resolve(servletRequest)));
    }
}
