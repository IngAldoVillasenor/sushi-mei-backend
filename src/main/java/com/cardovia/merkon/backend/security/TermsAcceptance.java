package com.cardovia.merkon.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "user_terms_acceptances", uniqueConstraints = @UniqueConstraint(
        name = "user_terms_acceptances_user_version_key", columnNames = {"user_id", "terms_version"}))
public class TermsAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "terms_version", nullable = false, length = 64)
    private String termsVersion;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    protected TermsAcceptance() {
    }

    static TermsAcceptance create(AppUser user, String termsVersion, String clientIp, Instant acceptedAt) {
        TermsAcceptance acceptance = new TermsAcceptance();
        acceptance.user = Objects.requireNonNull(user);
        acceptance.termsVersion = Objects.requireNonNull(termsVersion);
        acceptance.clientIp = clientIp;
        acceptance.acceptedAt = Objects.requireNonNull(acceptedAt);
        return acceptance;
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public String getTermsVersion() { return termsVersion; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public String getClientIp() { return clientIp; }
}
