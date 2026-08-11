package com.sushimei.sushimei.backend.security;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        ApplicationRole role,
        boolean active,
        long version) {

    static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.isActive(),
                user.getVersion());
    }
}