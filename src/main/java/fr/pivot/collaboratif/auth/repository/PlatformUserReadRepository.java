package fr.pivot.collaboratif.auth.repository;

import fr.pivot.collaboratif.auth.entity.PlatformUser;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Read-only access to {@code public.users} (EN08.3).
 *
 * <p>Extends Spring Data's bare {@link Repository} marker — not {@code JpaRepository} — so no
 * {@code save}/{@code delete} method is ever exposed: this repo never writes to {@code users}.
 */
public interface PlatformUserReadRepository extends Repository<PlatformUser, Long> {

    /**
     * Finds a platform user by primary key.
     *
     * @param id the {@code public.users.id} to look up
     * @return the matching user, or empty if none is found
     */
    Optional<PlatformUser> findById(Long id);
}
