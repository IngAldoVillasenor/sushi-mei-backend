package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, BusinessMembershipIntegrationTest.TestInfrastructureConfiguration.class})
class BusinessMembershipIntegrationTest {

    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private AuthService authService;
    @Autowired private PasswordPolicyService passwords;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    private Business legacyBusiness;

    @BeforeEach
    void cleanFixtures() {
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        memberships.deleteAll();
        users.deleteAll();
        businesses.findAll().stream()
                .filter(business -> business.getLegacyKey() == null)
                .forEach(businesses::delete);
        legacyBusiness = businesses.findByLegacyKey("SUSHIMEI_LEGACY").orElseThrow();
    }

    @Test
    void oneAuthorizedActiveMembershipAutoSelectsAndDrivesTheJwtRole() {
        AppUser user = createUser("legacy-manager", ApplicationRole.MANAGER);
        BusinessMembership membership = memberships.saveAndFlush(
                BusinessMembership.create(user, legacyBusiness, ApplicationRole.MANAGER, clock.instant()));

        AuthResponse response = authService.login(new LoginRequest(
                "legacy-manager", "una frase larga segura 123", "legacy-device", null, null), "127.0.0.1");
        Jwt jwt = jwtDecoder.decode(response.accessToken());

        assertThat(user.getEmail()).isNull();
        assertThat(user.getRegistrationState()).isEqualTo(AccountRegistrationState.LEGACY);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("MANAGER");
        assertThat(jwt.getClaimAsString("bid")).isEqualTo(membership.getBusiness().getId().toString());
        assertThat(jwt.getClaimAsString("mid")).isEqualTo(membership.getId().toString());
    }

    @Test
    void userWithoutMembershipCannotLogInAndLoginNeverCreatesOne() {
        AppUser user = createUser("no-membership", ApplicationRole.MANAGER);

        assertThatThrownBy(() -> authService.login(new LoginRequest(
                "no-membership", "una frase larga segura 123", "no-membership-device", null, null), "127.0.0.1"))
                .isInstanceOf(SecurityApiException.class)
                .extracting(exception -> ((SecurityApiException) exception).code())
                .isEqualTo("AUTH_BUSINESS_FORBIDDEN");
        assertThat(memberships.findByUserIdOrderByIdAsc(user.getId())).isEmpty();
    }

    @Test
    void membershipInBusinessAIsNeverAutoCreatedForTheOnlyActiveBusinessB() {
        AppUser user = createUser("a-only", ApplicationRole.CASHIER);
        Business businessA = businesses.saveAndFlush(Business.create("Business A", clock.instant()));
        memberships.saveAndFlush(BusinessMembership.create(user, businessA, ApplicationRole.CASHIER, clock.instant()));
        Business businessB = businesses.saveAndFlush(Business.create("Business B", clock.instant()));
        businessA.deactivate(clock.instant());
        businesses.saveAndFlush(businessA);

        assertThatThrownBy(() -> authService.login(new LoginRequest(
                "a-only", "una frase larga segura 123", "business-b-device", null, null), "127.0.0.1"))
                .isInstanceOf(SecurityApiException.class)
                .extracting(exception -> ((SecurityApiException) exception).code())
                .isEqualTo("AUTH_BUSINESS_FORBIDDEN");
        assertThat(memberships.findByUserIdAndBusinessId(user.getId(), businessB.getId())).isEmpty();
        assertThat(memberships.findByUserIdOrderByIdAsc(user.getId())).hasSize(1);
    }

    @Test
    void multipleMembershipsRequireSelectionAndRequestedMembershipMustBelongToTheUser() {
        AppUser user = createUser("multi-member", ApplicationRole.MANAGER);
        memberships.saveAndFlush(BusinessMembership.create(user, legacyBusiness, ApplicationRole.MANAGER, clock.instant()));
        Business secondBusiness = businesses.saveAndFlush(Business.create("Second verified business", clock.instant()));
        BusinessMembership secondMembership = memberships.saveAndFlush(
                BusinessMembership.create(user, secondBusiness, ApplicationRole.CASHIER, clock.instant()));

        assertThatThrownBy(() -> authService.login(new LoginRequest(
                "multi-member", "una frase larga segura 123", "multi-none", null, null), "127.0.0.1"))
                .isInstanceOf(SecurityApiException.class)
                .extracting(exception -> ((SecurityApiException) exception).code())
                .isEqualTo("AUTH_ACTIVE_BUSINESS_SELECTION_REQUIRED");
        assertThatThrownBy(() -> authService.login(new LoginRequest(
                "multi-member", "una frase larga segura 123", "multi-forbidden", null, null, 999999L), "127.0.0.1"))
                .isInstanceOf(SecurityApiException.class)
                .extracting(exception -> ((SecurityApiException) exception).code())
                .isEqualTo("AUTH_BUSINESS_FORBIDDEN");

        AuthResponse response = authService.login(new LoginRequest(
                "multi-member", "una frase larga segura 123", "multi-second", null, null, secondBusiness.getId()), "127.0.0.1");
        Jwt jwt = jwtDecoder.decode(response.accessToken());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CASHIER");
        assertThat(jwt.getClaimAsString("bid")).isEqualTo(secondBusiness.getId().toString());
        assertThat(jwt.getClaimAsString("mid")).isEqualTo(secondMembership.getId().toString());
    }

    @Test
    void duplicateMembershipIsRejectedEmailIsNormalizedAndBusinessNamesNeedNotBeUnique() {
        AppUser user = createUser("member-duplicate", ApplicationRole.CASHIER);
        memberships.saveAndFlush(BusinessMembership.create(user, legacyBusiness, ApplicationRole.CASHIER, clock.instant()));

        assertThatThrownBy(() -> memberships.saveAndFlush(
                BusinessMembership.create(user, legacyBusiness, ApplicationRole.CASHIER, clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);

        Business duplicateName = businesses.saveAndFlush(Business.create("Sushi Mei", clock.instant()));
        assertThat(duplicateName.getId()).isNotEqualTo(legacyBusiness.getId());

        AppUser emailUser = AppUser.create(
                "email-user", "Email user", passwords.encodeValidated("email-user", "una frase larga segura 123"),
                ApplicationRole.CASHIER, clock.instant());
        emailUser.setNormalizedEmail(UserManagementService.normalizeEmail("  USER@EXAMPLE.COM  "),
                AccountRegistrationState.PENDING_EMAIL_VERIFICATION, clock.instant());
        users.saveAndFlush(emailUser);
        assertThat(emailUser.getEmail()).isEqualTo("user@example.com");
        assertThat(emailUser.getRegistrationState()).isEqualTo(AccountRegistrationState.PENDING_EMAIL_VERIFICATION);
    }

    private AppUser createUser(String username, ApplicationRole role) {
        return users.saveAndFlush(AppUser.create(
                username,
                username,
                passwords.encodeValidated(username, "una frase larga segura 123"),
                role,
                clock.instant()));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
