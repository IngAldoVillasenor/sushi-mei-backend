package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountDeletionService {

    private final AppUserRepository users;
    private final BusinessRepository businesses;
    private final BusinessMembershipRepository memberships;
    private final AccountDeletionRequestRepository requests;
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwords;
    private final EmailVerificationTokenGenerator tokens;
    private final RegistrationRateLimitService rateLimit;
    private final AccountDeletionRequestTransaction requestTransaction;
    private final AccountDeletionDeliveryDispatcher delivery;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final DestructiveSessionCleanupService destructiveSessions;
    private final ReceiptFileCleanupService receiptFiles;

    AccountDeletionService(AppUserRepository users,
                           BusinessRepository businesses,
                           BusinessMembershipRepository memberships,
                           AccountDeletionRequestRepository requests,
                           EmailVerificationTokenRepository verificationTokens,
                           PasswordResetTokenRepository resetTokens,
                           PasswordEncoder passwords,
                           EmailVerificationTokenGenerator tokens,
                           RegistrationRateLimitService rateLimit,
                           AccountDeletionRequestTransaction requestTransaction,
                           AccountDeletionDeliveryDispatcher delivery,
                           SecurityAuditService audit,
                           Clock clock,
                           JdbcTemplate jdbc,
                           DestructiveSessionCleanupService destructiveSessions,
                           ReceiptFileCleanupService receiptFiles) {
        this.users = users;
        this.businesses = businesses;
        this.memberships = memberships;
        this.requests = requests;
        this.verificationTokens = verificationTokens;
        this.resetTokens = resetTokens;
        this.passwords = passwords;
        this.tokens = tokens;
        this.rateLimit = rateLimit;
        this.requestTransaction = requestTransaction;
        this.delivery = delivery;
        this.audit = audit;
        this.clock = clock;
        this.jdbc = jdbc;
        this.destructiveSessions = destructiveSessions;
        this.receiptFiles = receiptFiles;
    }

    AccountDeletionResponse requestPublic(AccountDeletionRequestInput input, String clientIp) {
        String email = PublicRegistrationInput.canonicalPublicEmailOrNull(input == null ? null : input.email());
        if (email == null) {
            throw invalidRequest();
        }
        rateLimit.checkAccountDeletionIdentity(email);
        EmailVerificationTokenGenerator.TokenMaterial material = tokens.generate();
        Optional<AccountDeletionRequestTransaction.IssuedRequest> issued = requestTransaction.issueForExistingAccount(
                email, material.hash(), clientIp);
        if (issued.isPresent()) {
            AccountDeletionRequestTransaction.IssuedRequest request = issued.orElseThrow();
            // requestTransaction returned only after its transaction committed.
            delivery.dispatch(
                    request.userId(),
                    request.recipient(),
                    request.requestId(),
                    request.expiresAt(),
                    material.plaintext());
        }
        return AccountDeletionResponse.accepted();
    }

    @Transactional
    AccountDeletionOutcome confirmPublic(AccountDeletionTokenInput input) {
        String hash;
        try {
            hash = tokens.hash(input == null ? null : input.token());
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
        Long userId = requests.findUserIdByTokenHash(hash).orElseThrow(AccountDeletionService::invalidToken);
        AppUser user = users.findByIdForUpdate(userId).orElseThrow(AccountDeletionService::invalidToken);
        Instant now = clock.instant();
        lockAffectedBusinesses(user);
        boolean pendingSupportRequired = user.getRegistrationState() == AccountRegistrationState.PENDING_EMAIL_VERIFICATION
                && !pendingOrphan(user);
        boolean lastOwnerBlocked = hasLastOwnerBlock(user);
        AccountDeletionRequest request = requests.findByTokenHashForUpdate(hash).orElseThrow(AccountDeletionService::invalidToken);
        if (!request.consumeIfUsable(now)) {
            throw invalidToken();
        }
        if (pendingSupportRequired) {
            return actionRequired(request, user, "PENDING_ACCOUNT_SUPPORT_REQUIRED",
                    AccountDeletionOutcome.PENDING_ACCOUNT_SUPPORT_REQUIRED, null);
        }
        if (lastOwnerBlocked) {
            return actionRequired(request, user, "LAST_OWNER", AccountDeletionOutcome.LAST_OWNER_ACTION_REQUIRED, null);
        }
        if (user.getRegistrationState() == AccountRegistrationState.PENDING_EMAIL_VERIFICATION) {
            deletePendingOrphanBusiness(user);
        }
        completeAccountDeletion(user, request, now, null);
        return AccountDeletionOutcome.COMPLETED;
    }

    @Transactional(readOnly = true)
    AccountDeletionImpact impact(Long userId) {
        List<AccountDeletionMembershipImpact> membershipsImpact = memberships.findByUserIdOrderByIdAsc(userId).stream()
                .map(membership -> new AccountDeletionMembershipImpact(
                        membership.getBusiness().getId(),
                        membership.getBusiness().getName(),
                        membership.getRole(),
                        membership.getRole() == ApplicationRole.OWNER && isLastActiveOwnerPreview(membership)))
                .toList();
        boolean required = membershipsImpact.stream().anyMatch(AccountDeletionMembershipImpact::lastActiveOwner);
        return new AccountDeletionImpact(!required, required, membershipsImpact);
    }

    @Transactional
    AccountDeletionOutcome confirmInApp(Long userId, String password) {
        AppUser user = users.findByIdForUpdate(userId).orElseThrow(AccountDeletionService::invalidToken);
        if (!passwords.matches(password, user.getPasswordHash())) {
            throw new SecurityApiException("ACCOUNT_DELETION_REAUTH_FAILED", HttpStatus.UNAUTHORIZED,
                    "La contraseña actual no es válida.");
        }
        Instant now = clock.instant();
        lockAffectedBusinesses(user);
        boolean lastOwnerBlocked = hasLastOwnerBlock(user);
        AccountDeletionRequest request = requests.save(AccountDeletionRequest.pending(
                user, AccountDeletionSource.IN_APP, null, now, now.plus(Duration.ofHours(1))));
        request.confirmInApp(now);
        if (lastOwnerBlocked) {
            return actionRequired(request, user, "LAST_OWNER", AccountDeletionOutcome.LAST_OWNER_ACTION_REQUIRED, userId);
        }
        completeAccountDeletion(user, request, now, userId);
        return AccountDeletionOutcome.COMPLETED;
    }

    @Transactional
    void deleteBusiness(Long actorUserId, Long businessId, String password) {
        // All destructive paths acquire AppUser before Business.
        AppUser actor = users.findByIdForUpdate(actorUserId).orElseThrow(this::forbiddenBusiness);
        Business business = businesses.findByIdForUpdate(businessId).orElseThrow(this::forbiddenBusiness);
        BusinessMembership membership = memberships.findByUserIdAndBusinessId(actorUserId, businessId)
                .orElseThrow(this::forbiddenBusiness);
        if (!business.isActive() || membership.getRole() != ApplicationRole.OWNER) {
            throw forbiddenBusiness();
        }
        if (!passwords.matches(password, actor.getPasswordHash())) {
            throw new SecurityApiException("BUSINESS_DELETION_REAUTH_FAILED", HttpStatus.UNAUTHORIZED,
                    "La contraseña actual no es válida.");
        }
        Set<String> receiptPaths = collectBusinessReceiptPaths(businessId);
        destructiveSessions.deleteForBusiness(businessId);
        deleteBusinessOwnedRows(businessId);
        memberships.deleteAll(memberships.findByBusinessIdOrderByIdAsc(businessId));
        businesses.delete(business);
        audit.record(
                SecurityAuditEventType.BUSINESS_DELETION_COMPLETED,
                actorUserId,
                null,
                null,
                null,
                null,
                SecurityAuditOutcome.SUCCESS,
                "BUSINESS_ID_" + businessId);
        receiptFiles.scheduleAfterCommit(actorUserId, businessId, receiptPaths);
    }

    @Transactional(readOnly = true)
    BusinessDeletionImpact businessImpact(Long actorUserId, Long businessId) {
        BusinessMembership membership = memberships.findByUserIdAndBusinessId(actorUserId, businessId)
                .orElseThrow(this::forbiddenBusiness);
        return new BusinessDeletionImpact(
                membership.getBusiness().getId(),
                membership.getBusiness().getName(),
                memberships.findByBusinessIdOrderByIdAsc(businessId).size(),
                membership.getRole() == ApplicationRole.OWNER,
                membership.getBusiness().isActive() && membership.getRole() == ApplicationRole.OWNER);
    }

    private AccountDeletionOutcome actionRequired(AccountDeletionRequest request,
                                                   AppUser user,
                                                   String actionCode,
                                                   AccountDeletionOutcome outcome,
                                                   Long actorUserId) {
        request.actionRequired(actionCode, clock.instant());
        audit.record(
                SecurityAuditEventType.ACCOUNT_DELETION_ACTION_REQUIRED,
                actorUserId,
                user.getId(),
                null,
                null,
                null,
                SecurityAuditOutcome.SUCCESS,
                actionCode);
        return outcome;
    }

    private void completeAccountDeletion(AppUser user,
                                         AccountDeletionRequest currentRequest,
                                         Instant now,
                                         Long actorUserId) {
        supersedeOtherPending(user.getId(), currentRequest, now);
        anonymize(user, now);
        currentRequest.complete(now);
        // Record after anonymize() scrubbed historical client/device metadata.
        audit.record(
                SecurityAuditEventType.ACCOUNT_DELETION_COMPLETED,
                actorUserId,
                user.getId(),
                null,
                null,
                null,
                SecurityAuditOutcome.SUCCESS,
                null);
    }

    private void lockAffectedBusinesses(AppUser user) {
        memberships.findByUserIdOrderByIdAsc(user.getId()).stream()
                .filter(membership -> membership.getBusiness().isActive() && membership.getRole() == ApplicationRole.OWNER)
                .map(membership -> membership.getBusiness().getId())
                .distinct()
                .sorted()
                .forEach(id -> businesses.findByIdForUpdate(id).orElseThrow(AccountDeletionService::invalidToken));
    }

    private boolean hasLastOwnerBlock(AppUser user) {
        return memberships.findByUserIdOrderByIdAsc(user.getId()).stream()
                .filter(membership -> membership.getBusiness().isActive() && membership.getRole() == ApplicationRole.OWNER)
                .anyMatch(this::isLastActiveOwner);
    }

    private boolean isLastActiveOwner(BusinessMembership membership) {
        return memberships.findActiveOwnersForBusinessForUpdate(membership.getBusiness().getId()).size() == 1;
    }

    private boolean isLastActiveOwnerPreview(BusinessMembership membership) {
        return memberships.countActiveOwnersForBusiness(membership.getBusiness().getId()) == 1;
    }

    private boolean pendingOrphan(AppUser user) {
        List<BusinessMembership> userMemberships = memberships.findByUserIdOrderByIdAsc(user.getId());
        return userMemberships.size() == 1
                && memberships.findByBusinessIdOrderByIdAsc(userMemberships.get(0).getBusiness().getId()).size() == 1
                && !hasOperationalData(userMemberships.get(0).getBusiness().getId());
    }

    private boolean hasOperationalData(Long businessId) {
        return jdbc.queryForObject("select (exists(select 1 from orders where business_id=?) "
                        + "or exists(select 1 from business_days where business_id=?) "
                        + "or exists(select 1 from menu_items where business_id=?))",
                Boolean.class, businessId, businessId, businessId);
    }

    private void deletePendingOrphanBusiness(AppUser user) {
        Long businessId = memberships.findByUserIdOrderByIdAsc(user.getId()).get(0).getBusiness().getId();
        memberships.deleteAll(memberships.findByBusinessIdOrderByIdAsc(businessId));
        businesses.delete(businesses.findByIdForUpdate(businessId).orElseThrow());
    }

    private void supersedeOtherPending(Long userId, AccountDeletionRequest current, Instant now) {
        requests.findPendingByUserIdForUpdate(userId).stream()
                .filter(request -> !request.getId().equals(current.getId()))
                .forEach(request -> request.supersede(now));
    }

    private void anonymize(AppUser user, Instant now) {
        destructiveSessions.deleteForUser(user.getId());
        verificationTokens.deleteAll(verificationTokens.findByUserIdOrderByCreatedAtAscIdAsc(user.getId()));
        resetTokens.deleteAll(resetTokens.findByUserIdOrderByCreatedAtAscIdAsc(user.getId()));
        jdbc.update("update user_terms_acceptances set client_ip = null where user_id = ?", user.getId());
        jdbc.update("update security_audit_events set client_ip = null, device_id = null where actor_user_id = ? or subject_user_id = ?",
                user.getId(), user.getId());
        memberships.deleteAll(memberships.findByUserIdOrderByIdAsc(user.getId()));
        user.anonymizeDeleted("deleted-" + UUID.randomUUID(), passwords.encode(UUID.randomUUID().toString()), now);
    }

    private void deleteBusinessOwnedRows(Long id) {
        String[] statements = {
                "delete from order_line_selection_component_omissions where selection_snapshot_id in (select s.id from order_line_selection_snapshots s join order_lines l on l.id=s.order_line_id join orders o on o.id=l.order_id where o.business_id=?)",
                "delete from order_line_component_omissions where order_line_id in (select l.id from order_lines l join orders o on o.id=l.order_id where o.business_id=?)",
                "delete from order_line_selection_snapshots where order_line_id in (select l.id from order_lines l join orders o on o.id=l.order_id where o.business_id=?)",
                "delete from vendis_payment_snapshots where order_id in (select id from orders where business_id=?)",
                "delete from vendis_order_snapshots where order_id in (select id from orders where business_id=?)",
                "delete from order_lines where order_id in (select id from orders where business_id=?)",
                "delete from orders where business_id=?", "delete from business_day_cash_expenses where business_id=?",
                "delete from business_day_closures where business_day_id in (select id from business_days where business_id=?)",
                "delete from business_days where business_id=?", "delete from business_day_operation_locks where business_id=?",
                "delete from cart_items where cart_id in (select id from cart where business_id=?)", "delete from cart where business_id=?",
                "delete from conversation_sessions where business_id=?", "delete from whatsapp_inbound_messages where business_id=?",
                "delete from promotion_targets where promotion_id in (select id from promotions where business_id=?)",
                "delete from promotion_weekdays where promotion_id in (select id from promotions where business_id=?)",
                "delete from promotions where business_id=?", "delete from menu_item_tags where menu_item_id in (select id from menu_items where business_id=?)",
                "delete from menu_selection_rules where selection_group_id in (select g.id from menu_selection_groups g join menu_items i on i.id=g.parent_menu_item_id where i.business_id=?) or target_menu_item_id in (select id from menu_items where business_id=?)",
                "delete from menu_selection_groups where parent_menu_item_id in (select id from menu_items where business_id=?)",
                "delete from menu_item_default_components where menu_item_id in (select id from menu_items where business_id=?)",
                "delete from menu_items where business_id=?", "delete from catalog_tags where business_id=?"
        };
        for (String statement : statements) {
            int count = (int) statement.chars().filter(character -> character == '?').count();
            Object[] arguments = new Object[count];
            Arrays.fill(arguments, id);
            jdbc.update(statement, arguments);
        }
    }

    private Set<String> collectBusinessReceiptPaths(Long businessId) {
        Set<String> paths = new LinkedHashSet<>();
        addReceiptPaths(paths, "select transfer_receipt_path from orders where business_id = ?", businessId);
        addReceiptPaths(paths, "select transfer_receipt_path from conversation_sessions where business_id = ?", businessId);
        return paths;
    }

    private void addReceiptPaths(Set<String> paths, String query, Long businessId) {
        jdbc.query(query, resultSet -> {
            String path = resultSet.getString(1);
            if (path != null && !path.isBlank()) {
                paths.add(path);
            }
        }, businessId);
    }

    private static SecurityApiException invalidRequest() {
        return new SecurityApiException("ACCOUNT_DELETION_INVALID_REQUEST", HttpStatus.BAD_REQUEST,
                "Solicitud de eliminación inválida.");
    }

    private static SecurityApiException invalidToken() {
        return new SecurityApiException("ACCOUNT_DELETION_INVALID_TOKEN", HttpStatus.BAD_REQUEST,
                "El enlace de eliminación no es válido o ha vencido.");
    }

    static SecurityApiException lastOwner() {
        return new SecurityApiException("ACCOUNT_DELETION_LAST_OWNER_ACTION_REQUIRED", HttpStatus.CONFLICT,
                "Debes transferir la propiedad o eliminar el negocio antes de eliminar tu cuenta.");
    }

    static SecurityApiException actionRequired() {
        return new SecurityApiException("ACCOUNT_DELETION_ACTION_REQUIRED", HttpStatus.CONFLICT,
                "La cuenta requiere una acción adicional antes de poder eliminarse automáticamente.");
    }

    private SecurityApiException forbiddenBusiness() {
        return new SecurityApiException("BUSINESS_DELETION_FORBIDDEN", HttpStatus.FORBIDDEN,
                "No tienes permisos para eliminar este negocio.");
    }

    record AccountDeletionImpact(boolean canDeleteAccount,
                                 boolean requiresAction,
                                 List<AccountDeletionMembershipImpact> memberships) {
    }

    record AccountDeletionMembershipImpact(Long businessId,
                                           String businessName,
                                           ApplicationRole role,
                                           boolean lastActiveOwner) {
    }

    record BusinessDeletionImpact(Long businessId,
                                  String businessName,
                                  int memberCount,
                                  boolean currentUserOwner,
                                  boolean canDeleteBusiness) {
    }
}
