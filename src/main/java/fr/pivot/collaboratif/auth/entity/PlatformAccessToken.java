package fr.pivot.collaboratif.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read-only mirror of {@code public.access_tokens}, owned and written exclusively by
 * {@code pivot-core} — never persisted, updated, or deleted from this repo (EN08.3, ADR-022).
 *
 * <p>Mapped explicitly to schema {@code public} (this repo's own Flyway only manages
 * {@code collaboratif}) and deliberately minimal — only the columns
 * {@link fr.pivot.collaboratif.auth.TokenValidationService} needs to duplicate {@code
 * fr.pivot.auth.service.TokenService#validate(String)}'s algorithm. Fields such as {@code
 * device_name}, {@code user_agent}, {@code last_used_at} etc. are intentionally not mapped:
 * this repo never touches session/device metadata, only validates the bearer token.
 *
 * <p>No setters, no {@code @GeneratedValue} — nothing here is ever written back. Only {@code
 * findBy...} query methods on {@link fr.pivot.collaboratif.auth.repository.AccessTokenReadRepository}
 * ever touch this entity.
 */
@Entity
@Table(schema = "public", name = "access_tokens")
public class PlatformAccessToken {

    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex-encoded hash of the raw bearer token — the raw token itself is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Lifecycle status, lowercase in the database ({@code active}/{@code expired}/{@code revoked}). */
    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** No-argument constructor required by JPA. */
    protected PlatformAccessToken() {
    }

    /** @return database primary key */
    public Long getId() {
        return id;
    }

    /** @return the owning {@code public.users.id} */
    public Long getUserId() {
        return userId;
    }

    /** @return the SHA-256 hex-encoded hash of the raw token */
    public String getTokenHash() {
        return tokenHash;
    }

    /** @return the lowercase lifecycle status ({@code active}/{@code expired}/{@code revoked}) */
    public String getStatus() {
        return status;
    }

    /** @return the absolute expiry timestamp */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** @return the token creation timestamp, used for tenant-invalidation comparison */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
