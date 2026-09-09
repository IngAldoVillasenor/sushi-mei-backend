package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapOwnerInitializer implements ApplicationRunner {

    private final MerkonSecurityProperties properties;
    private final AppUserRepository users;
    private final LegacyBusinessResolver legacyBusiness;
    private final BusinessMembershipRepository memberships;
    private final PasswordPolicyService passwords;
    private final SecurityAuditService audit;
    private final Clock clock;

    public BootstrapOwnerInitializer(MerkonSecurityProperties properties,
                                     AppUserRepository users,
                                     LegacyBusinessResolver legacyBusiness,
                                     BusinessMembershipRepository memberships,
                                     PasswordPolicyService passwords,
                                     SecurityAuditService audit,
                                     Clock clock) {
        this.properties = properties;
        this.users = users;
        this.legacyBusiness = legacyBusiness;
        this.memberships = memberships;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        boolean usernameConfigured = present(properties.bootstrapOwnerUsername());
        boolean passwordConfigured = present(properties.bootstrapOwnerPassword());
        if (usernameConfigured != passwordConfigured) {
            throw new IllegalStateException("Both bootstrap owner credentials must be configured together");
        }
        if (!usernameConfigured || users.count() != 0) {
            return;
        }

        String username = AuthService.normalizeUsername(properties.bootstrapOwnerUsername());
        AppUser owner = AppUser.create(
                username,
                displayName(properties.bootstrapOwnerDisplayName()),
                passwords.encodeValidated(username, properties.bootstrapOwnerPassword()),
                ApplicationRole.OWNER,
                clock.instant());
        users.saveAndFlush(owner);
        memberships.save(BusinessMembership.create(owner, legacyBusiness.requireLegacyBusiness(), ApplicationRole.OWNER, clock.instant()));
        audit.record(
                SecurityAuditEventType.USER_CREATED,
                null,
                owner.getId(),
                null,
                null,
                null,
                SecurityAuditOutcome.SUCCESS,
                "BOOTSTRAP");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String displayName(String value) {
        return value == null || value.isBlank() ? "Owner" : value.trim();
    }
}
