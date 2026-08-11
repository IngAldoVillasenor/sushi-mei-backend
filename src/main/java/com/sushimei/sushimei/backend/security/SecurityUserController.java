package com.sushimei.sushimei.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/security")
public class SecurityUserController {

    private final UserManagementService users;

    public SecurityUserController(UserManagementService users) {
        this.users = users;
    }

    @GetMapping("/users")
    public List<UserResponse> list() {
        return users.list();
    }

    @GetMapping("/users/{id}")
    public UserResponse get(@PathVariable Long id) {
        return users.get(id);
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request,
                                               @AuthenticationPrincipal Jwt jwt,
                                               HttpServletRequest servletRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(users.create(request, actorUserId(jwt), servletRequest.getRemoteAddr()));
    }

    @PutMapping("/users/{id}")
    public UserResponse update(@PathVariable Long id,
                               @Valid @RequestBody UpdateUserRequest request,
                               @AuthenticationPrincipal Jwt jwt,
                               HttpServletRequest servletRequest) {
        return users.update(id, request, actorUserId(jwt), servletRequest.getRemoteAddr());
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id,
                                              @Valid @RequestBody ResetPasswordRequest request,
                                              @AuthenticationPrincipal Jwt jwt,
                                              HttpServletRequest servletRequest) {
        users.resetPassword(id, request, actorUserId(jwt), servletRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{id}/sessions")
    public List<SessionResponse> sessions(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return users.sessions(id, UUID.fromString(jwt.getClaimAsString("sid")));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id,
                                       @AuthenticationPrincipal Jwt jwt,
                                       HttpServletRequest servletRequest) {
        users.revokeSession(id, actorUserId(jwt), servletRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    private static Long actorUserId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}