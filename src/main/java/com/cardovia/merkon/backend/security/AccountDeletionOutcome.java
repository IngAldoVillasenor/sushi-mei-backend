package com.cardovia.merkon.backend.security;

enum AccountDeletionOutcome {
    COMPLETED,
    LAST_OWNER_ACTION_REQUIRED,
    PENDING_ACCOUNT_SUPPORT_REQUIRED
}
