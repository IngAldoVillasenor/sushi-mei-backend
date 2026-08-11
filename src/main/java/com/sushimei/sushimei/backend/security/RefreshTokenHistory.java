package com.sushimei.sushimei.backend.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "auth_refresh_token_history")
public class RefreshTokenHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AuthSession session;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "rotated_at", nullable = false)
    private Instant rotatedAt;

    protected RefreshTokenHistory() {
    }

    static RefreshTokenHistory create(AuthSession session, String tokenHash, Instant rotatedAt) {
        RefreshTokenHistory history = new RefreshTokenHistory();
        history.session = session;
        history.tokenHash = tokenHash;
        history.rotatedAt = rotatedAt;
        return history;
    }
}