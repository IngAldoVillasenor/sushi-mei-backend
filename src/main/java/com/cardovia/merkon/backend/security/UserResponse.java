package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.BusinessMembership;
public record UserResponse(
        Long id,
        String username,
        String displayName,
        String email,
        AccountRegistrationState registrationState,
        ApplicationRole role,
        boolean active,
        long version,
        Long activeBusinessId,
        Long activeMembershipId) {

    static UserResponse from(AppUser user, BusinessMembership membership) {
        return new UserResponse(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getRegistrationState(),
                membership.getRole(), user.isActive(), user.getVersion(), membership.getBusiness().getId(), membership.getId());
    }
}
