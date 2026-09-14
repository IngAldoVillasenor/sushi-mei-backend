package com.cardovia.merkon.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class AccountDeletionController {
    private final AccountDeletionService deletion;
    private final RegistrationClientAddressResolver addresses;
    AccountDeletionController(AccountDeletionService deletion, RegistrationClientAddressResolver addresses) { this.deletion = deletion; this.addresses = addresses; }
    @PostMapping("/api/v1/account-deletion/request")
    ResponseEntity<AccountDeletionResponse> request(@Valid @RequestBody AccountDeletionRequestInput input, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(deletion.requestPublic(input, addresses.resolve(request)));
    }
    @PostMapping("/api/v1/account-deletion/confirm")
    ResponseEntity<Void> confirm(@Valid @RequestBody AccountDeletionTokenInput input) { return completedOrActionRequired(deletion.confirmPublic(input)); }
    @GetMapping("/api/v1/auth/account-deletion/impact")
    AccountDeletionService.AccountDeletionImpact impact(@AuthenticationPrincipal Jwt jwt) { return deletion.impact(userId(jwt)); }
    @PostMapping("/api/v1/auth/account-deletion/confirm")
    ResponseEntity<Void> confirmInApp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeletionReauthenticationRequest input) { return completedOrActionRequired(deletion.confirmInApp(userId(jwt), input.currentPassword())); }
    @GetMapping("/api/v1/auth/business-deletion/impact")
    AccountDeletionService.BusinessDeletionImpact businessImpact(@AuthenticationPrincipal Jwt jwt) { return deletion.businessImpact(userId(jwt), businessId(jwt)); }
    @PostMapping("/api/v1/auth/business-deletion/confirm")
    ResponseEntity<Void> confirmBusiness(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeletionReauthenticationRequest input) { deletion.deleteBusiness(userId(jwt), businessId(jwt), input.currentPassword()); return ResponseEntity.noContent().build(); }
    private static ResponseEntity<Void> completedOrActionRequired(AccountDeletionOutcome outcome) {
        if (outcome == AccountDeletionOutcome.LAST_OWNER_ACTION_REQUIRED) throw AccountDeletionService.lastOwner();
        if (outcome == AccountDeletionOutcome.PENDING_ACCOUNT_SUPPORT_REQUIRED) throw AccountDeletionService.actionRequired();
        return ResponseEntity.noContent().build();
    }
    private static Long userId(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }
    private static Long businessId(Jwt jwt) { return Long.valueOf(jwt.getClaimAsString("bid")); }
}
