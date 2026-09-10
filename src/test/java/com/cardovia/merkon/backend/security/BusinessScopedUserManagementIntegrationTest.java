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
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, BusinessScopedUserManagementIntegrationTest.TestInfrastructureConfiguration.class})
class BusinessScopedUserManagementIntegrationTest {

    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private AuthSessionRepository sessions;
    @Autowired private AuthService authService;
    @Autowired private UserManagementService userManagement;
    @Autowired private PublicRegistrationService registrations;
    @Autowired private PasswordPolicyService passwords;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private Business businessA;
    private Business businessB;
    private AppUser ownerA;
    private AppUser ownerB;
    private AppUser shared;
    private AppUser aOnly;
    private AppUser bOnly;

    @BeforeEach
    void setUpTwoBusinesses() {
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.email_verification_tokens");
        jdbcTemplate.update("delete from public.registration_rate_limit_buckets");
        jdbcTemplate.update("delete from public.user_terms_acceptances");
        memberships.deleteAll();
        users.deleteAll();
        businesses.findAll().stream()
                .filter(business -> business.getLegacyKey() == null)
                .forEach(businesses::delete);
        businessA = businesses.saveAndFlush(Business.create("Business A", clock.instant()));
        businessB = businesses.saveAndFlush(Business.create("Business B", clock.instant()));
        ownerA = user("owner-a", ApplicationRole.OWNER);
        ownerB = user("owner-b", ApplicationRole.OWNER);
        shared = user("shared-user", ApplicationRole.CASHIER);
        aOnly = user("a-only-user", ApplicationRole.CASHIER);
        bOnly = user("b-only-user", ApplicationRole.KITCHEN);
        member(ownerA, businessA, ApplicationRole.OWNER);
        member(ownerB, businessB, ApplicationRole.OWNER);
        member(shared, businessA, ApplicationRole.CASHIER);
        member(shared, businessB, ApplicationRole.KITCHEN);
        member(aOnly, businessA, ApplicationRole.CASHIER);
        member(bOnly, businessB, ApplicationRole.KITCHEN);
    }

    @Test
    void businessAdministrationCannotReadMutateResetOrRevokeAcrossBusinesses() {
        authService.login(new LoginRequest(
                "b-only-user", "una frase larga segura 123", "b-only-device", null, null), "127.0.0.1");
        UUID businessBSessionId = sessions.findActiveByUserAndDevice(bOnly.getId(), "b-only-device").get(0).getId();

        assertThat(userManagement.list(ownerA.getId(), businessA.getId()))
                .extracting(UserResponse::id)
                .containsExactlyInAnyOrder(ownerA.getId(), shared.getId(), aOnly.getId())
                .doesNotContain(ownerB.getId(), bOnly.getId());
        assertThat(userManagement.list(ownerA.getId(), businessA.getId()))
                .filteredOn(response -> response.id().equals(shared.getId()))
                .singleElement()
                .extracting(UserResponse::role)
                .isEqualTo(ApplicationRole.CASHIER);

        assertCode(() -> userManagement.get(bOnly.getId(), ownerA.getId(), businessA.getId()), "USER_NOT_FOUND");
        assertCode(() -> userManagement.update(bOnly.getId(), new UpdateUserRequest(
                "B only", ApplicationRole.CASHIER, true, bOnly.getVersion()), ownerA.getId(), businessA.getId(), "127.0.0.1"),
                "USER_NOT_FOUND");
        assertCode(() -> userManagement.resetPassword(bOnly.getId(), new ResetPasswordRequest(
                "otra frase larga segura 123", bOnly.getVersion()), ownerA.getId(), businessA.getId(), "127.0.0.1"),
                "USER_NOT_FOUND");
        assertCode(() -> userManagement.sessions(bOnly.getId(), businessBSessionId, ownerA.getId(), businessA.getId()),
                "USER_NOT_FOUND");
        assertCode(() -> userManagement.revokeSession(businessBSessionId, ownerA.getId(), businessA.getId(), "127.0.0.1"),
                "AUTH_SESSION_REVOKED");
        assertThat(sessions.findByIdWithContext(businessBSessionId).orElseThrow().isRevoked()).isFalse();
    }

    @Test
    void membershipRoleUpdatesAndLastOwnerProtectionAreIndependentPerBusiness() {
        authService.login(new LoginRequest("shared-user", "una frase larga segura 123", "shared-business-b", null, null,
                businessB.getId()), "127.0.0.1");
        UUID businessBSessionId = sessions.findActiveByUserAndDevice(shared.getId(), "shared-business-b").get(0).getId();

        userManagement.update(shared.getId(), new UpdateUserRequest(
                shared.getDisplayName(), ApplicationRole.MANAGER, true,
                users.findById(shared.getId()).orElseThrow().getVersion()),
                ownerA.getId(), businessA.getId(), "127.0.0.1");
        assertThat(memberships.findByUserIdAndBusinessId(shared.getId(), businessA.getId()).orElseThrow().getRole())
                .isEqualTo(ApplicationRole.MANAGER);
        assertThat(memberships.findByUserIdAndBusinessId(shared.getId(), businessB.getId()).orElseThrow().getRole())
                .isEqualTo(ApplicationRole.KITCHEN);
        assertThat(sessions.findByIdWithContext(businessBSessionId).orElseThrow().isRevoked()).isFalse();

        assertCode(() -> userManagement.update(ownerA.getId(), new UpdateUserRequest(
                ownerA.getDisplayName(), ApplicationRole.MANAGER, true, ownerA.getVersion()),
                ownerA.getId(), businessA.getId(), "127.0.0.1"), "INVALID_USER");
        AppUser secondOwnerA = user("owner-a-two", ApplicationRole.OWNER);
        member(secondOwnerA, businessA, ApplicationRole.OWNER);
        userManagement.update(ownerA.getId(), new UpdateUserRequest(
                ownerA.getDisplayName(), ApplicationRole.MANAGER, true, ownerA.getVersion()),
                secondOwnerA.getId(), businessA.getId(), "127.0.0.1");
        assertThat(memberships.findByUserIdAndBusinessId(ownerB.getId(), businessB.getId()).orElseThrow().getRole())
                .isEqualTo(ApplicationRole.OWNER);
    }

    @Test
    void sharedIdentityGlobalMutationsFailClosedAndRequestedLoginBusinessCannotCrossMembershipBoundaries() {
        assertCode(() -> userManagement.update(shared.getId(), new UpdateUserRequest(
                "Changed globally", ApplicationRole.MANAGER, true, shared.getVersion()),
                ownerA.getId(), businessA.getId(), "127.0.0.1"), "USER_SHARED_IDENTITY_MUTATION_FORBIDDEN");
        assertCode(() -> userManagement.resetPassword(shared.getId(), new ResetPasswordRequest(
                "otra frase larga segura 123", shared.getVersion()), ownerA.getId(), businessA.getId(), "127.0.0.1"),
                "USER_SHARED_IDENTITY_MUTATION_FORBIDDEN");
        assertCode(() -> authService.login(new LoginRequest(
                "a-only-user", "una frase larga segura 123", "cross-business-device", null, null, businessB.getId()), "127.0.0.1"),
                "AUTH_BUSINESS_FORBIDDEN");
    }

    @Test
    void ownerHttpAdministrationUsesTheJwtBusinessContextRatherThanAnyClientTenantParameter() throws Exception {
        String ownerAToken = authService.login(new LoginRequest(
                "owner-a", "una frase larga segura 123", "owner-a-http", null, null), "127.0.0.1").accessToken();
        authService.login(new LoginRequest(
                "b-only-user", "una frase larga segura 123", "b-only-http", null, null), "127.0.0.1");
        UUID businessBSessionId = sessions.findActiveByUserAndDevice(bOnly.getId(), "b-only-http").get(0).getId();
        String update = objectMapper.writeValueAsString(new UpdateUserRequest(
                "B only", ApplicationRole.CASHIER, true, bOnly.getVersion()));
        String reset = objectMapper.writeValueAsString(new ResetPasswordRequest(
                "otra frase larga segura 123", bOnly.getVersion()));

        mockMvc.perform(get("/api/v1/security/users").header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + bOnly.getId() + ")]").isEmpty());
        mockMvc.perform(get("/api/v1/security/users/{id}", bOnly.getId()).header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(put("/api/v1/security/users/{id}", bOnly.getId()).header("Authorization", "Bearer " + ownerAToken)
                        .contentType(APPLICATION_JSON).content(update))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/security/users/{id}/reset-password", bOnly.getId())
                        .header("Authorization", "Bearer " + ownerAToken).contentType(APPLICATION_JSON).content(reset))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/security/users/{id}/sessions", bOnly.getId())
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/security/sessions/{id}", businessBSessionId)
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void administrativeEmailShapedUsernamesUseThePublicCanonicalIdentityAndReserveItsAliases() {
        UserResponse canonical = userManagement.create(new CreateUserRequest(
                " OWNER@B\u00dcCHER.EXAMPLE ", "Canonical administrator",
                "una frase larga segura 123", ApplicationRole.CASHIER),
                ownerA.getId(), businessA.getId(), "127.0.0.1");
        assertThat(canonical.username()).isEqualTo("owner@xn--bcher-kva.example");
        assertThat(users.findByUsername("owner@xn--bcher-kva.example")).isPresent();

        UserResponse legacy = userManagement.create(new CreateUserRequest(
                "LEGACY@NOT-EMAIL", "Legacy at username",
                "una frase larga segura 123", ApplicationRole.KITCHEN),
                ownerA.getId(), businessA.getId(), "127.0.0.1");
        assertThat(legacy.username()).isEqualTo("legacy@not-email");
    }

    @Test
    void publicCanonicalIdentityPreventsAdministrativeUnicodeEquivalentCreation() {
        registrations.register(new PublicRegistrationRequest(
                "owner@xn--bcher-kva.example", "Public owner",
                "una frase larga segura 123", "Public owner business", true), "198.51.100.20");

        assertCode(() -> userManagement.create(new CreateUserRequest(
                "OWNER@B\u00dcCHER.EXAMPLE", "Colliding administrator",
                "una frase larga segura 123", ApplicationRole.CASHIER),
                ownerA.getId(), businessA.getId(), "127.0.0.1"), "INVALID_USER");
        assertThat(users.findByUsername("owner@xn--bcher-kva.example")).isPresent();
        assertThat(users.findByUsername("owner@b\u00fccher.example")).isEmpty();
    }

    private AppUser user(String username, ApplicationRole legacyRole) {
        return users.saveAndFlush(AppUser.create(username, username,
                passwords.encodeValidated(username, "una frase larga segura 123"), legacyRole, clock.instant()));
    }

    private void member(AppUser user, Business business, ApplicationRole role) {
        memberships.saveAndFlush(BusinessMembership.create(user, business, role, clock.instant()));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable)
                .isInstanceOf(SecurityApiException.class)
                .extracting(exception -> ((SecurityApiException) exception).code())
                .isEqualTo(code);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
