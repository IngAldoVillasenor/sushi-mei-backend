package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The durable account, tenant, membership, consent, and audit unit of work. */
@Component
class PublicRegistrationCreationTransaction {

    private final AppUserRepository users;
    private final BusinessRepository businesses;
    private final BusinessMembershipRepository memberships;
    private final TermsAcceptanceRepository termsAcceptances;
    private final PublicRegistrationProperties properties;
    private final SecurityAuditService audit;
    private final Clock clock;

    PublicRegistrationCreationTransaction(AppUserRepository users,
                                          BusinessRepository businesses,
                                          BusinessMembershipRepository memberships,
                                          TermsAcceptanceRepository termsAcceptances,
                                          PublicRegistrationProperties properties,
                                          SecurityAuditService audit,
                                          Clock clock) {
        this.users = users;
        this.businesses = businesses;
        this.memberships = memberships;
        this.termsAcceptances = termsAcceptances;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    RegisteredAccount create(PublicRegistrationInput input, String passwordHash, String clientIp) {
        Instant now = clock.instant();
        AppUser user = users.saveAndFlush(AppUser.createPendingRegistration(
                input.normalizedEmail(), input.displayName(), passwordHash, now));
        Business business = businesses.saveAndFlush(Business.create(input.businessName(), now));
        BusinessMembership membership = memberships.saveAndFlush(BusinessMembership.create(
                user, business, ApplicationRole.OWNER, now));
        termsAcceptances.saveAndFlush(TermsAcceptance.create(user, properties.termsVersion(), clientIp, now));
        audit.record(
                SecurityAuditEventType.REGISTRATION_ACCEPTED,
                null,
                user.getId(),
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
        return new RegisteredAccount(user, business, membership);
    }

    record RegisteredAccount(AppUser user, Business business, BusinessMembership membership) {
    }
}
