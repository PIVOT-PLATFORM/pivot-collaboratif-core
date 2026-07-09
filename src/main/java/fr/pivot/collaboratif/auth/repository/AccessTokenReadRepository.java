package fr.pivot.collaboratif.auth.repository;

import fr.pivot.collaboratif.auth.entity.PlatformAccessToken;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Read-only access to {@code public.access_tokens} (EN08.3).
 *
 * <p>Extends Spring Data's bare {@link Repository} marker — not {@code JpaRepository} — so no
 * {@code save}/{@code delete} method is ever exposed on this interface: this repo never writes
 * to {@code access_tokens} (that stays {@code pivot-core-app}'s exclusive job, including the
 * {@code last_used_at} touch on every validation).
 */
public interface AccessTokenReadRepository extends Repository<PlatformAccessToken, Long> {

    /**
     * Finds an access token by its SHA-256 hash and lifecycle status.
     *
     * @param tokenHash the SHA-256 hex-encoded hash of the raw bearer token
     * @param status    the lowercase lifecycle status to match (e.g. {@code "active"})
     * @return the matching token, or empty if none is found
     */
    Optional<PlatformAccessToken> findByTokenHashAndStatus(String tokenHash, String status);
}
