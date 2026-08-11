package com.sushimei.sushimei.backend.security;

import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapOwnerInitializer implements ApplicationRunner {

    private final SushiMeiSecurityProperties properties;
    private final AppUserRepository users;
    private final PasswordPolicyService passwords;
    private final SecurityAuditService audit;
    private final Clock clock;

    public BootstrapOwnerInitializer(SushiMeiSecurityProperties properties,
                                     AppUserRepository users,
                                     PasswordPolicyService passwords,
                                     SecurityAuditService audit,
                                     Clock clock) {
        this.properties = properties;
        this.users = users;
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
        users.save(owner);
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