package fr.pivot.collaboratif.whiteboard.ws;

import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Caches board membership decisions for STOMP frame authorization.
 *
 * <p>Two cache tiers are maintained in Redis:
 * <ul>
 *   <li><b>Auth cache</b> — key {@code ws:auth:{tenantId}:{boardId}:{userId}}, TTL 5 s.
 *       Used for every SUBSCRIBE and SEND frame to verify membership. Short TTL ensures
 *       role revocations take effect within the SLA (≤ 5 s) defined in EN08.1.</li>
 *   <li><b>Heartbeat cache</b> — key {@code ws:heartbeat:{tenantId}:{boardId}:{userId}},
 *       TTL 5 min. Refreshed on every SEND, used for liveness checks only (presence
 *       heartbeat, not an authoritative membership decision).</li>
 * </ul>
 *
 * <p>On a cache miss the membership is verified against PostgreSQL using
 * {@link BoardMemberRepository} and {@link BoardRepository}, with tenant isolation
 * enforced by comparing the board's {@code tenantId} to the requesting principal's
 * {@code tenantId}.
 */
@Service
public class MembershipCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(MembershipCacheService.class);
    private static final Duration AUTH_TTL = Duration.ofSeconds(5);
    private static final Duration HEARTBEAT_TTL = Duration.ofMinutes(5);
    private static final String AUTH_PREFIX = "ws:auth:";
    private static final String HEARTBEAT_PREFIX = "ws:heartbeat:";
    private static final String MEMBER_VALUE = "1";
    private static final String NON_MEMBER_VALUE = "0";

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the required dependencies.
     *
     * @param boardRepository       repository for board tenant-isolation checks
     * @param boardMemberRepository repository for membership lookups
     * @param redisTemplate         Redis client for caching
     */
    public MembershipCacheService(
            final BoardRepository boardRepository,
            final BoardMemberRepository boardMemberRepository,
            final StringRedisTemplate redisTemplate) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns {@code true} when the given user is a member of the given board within the
     * given tenant, using a Redis cache with a 5-second TTL to satisfy the EN08.1
     * revocation SLA.
     *
     * <p>A cache miss triggers a database lookup that enforces tenant isolation: a board
     * belonging to a different tenant is treated as non-existent (returns {@code false})
     * so that boardId collisions across tenants do not reveal cross-tenant data.
     *
     * @param tenantId the requesting user's tenant UUID
     * @param boardId  the board UUID
     * @param userId   the requesting user UUID
     * @return {@code true} if the user is a member of the board in that tenant
     */
    public boolean isMember(final UUID tenantId, final UUID boardId, final UUID userId) {
        String key = AUTH_PREFIX + tenantId + ":" + boardId + ":" + userId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return MEMBER_VALUE.equals(cached);
        }
        boolean member = lookupMembership(tenantId, boardId, userId);
        redisTemplate.opsForValue().set(key, member ? MEMBER_VALUE : NON_MEMBER_VALUE, AUTH_TTL);
        LOG.debug("Membership cache miss: user={} board={} tenant={} result={}", userId, boardId, tenantId, member);
        return member;
    }

    /**
     * Refreshes the heartbeat cache entry for a connected user on a board, extending the
     * TTL to 5 minutes. Called on every SEND frame for liveness tracking; not used for
     * authoritative membership decisions.
     *
     * @param tenantId the user's tenant UUID
     * @param boardId  the board UUID
     * @param userId   the user UUID
     */
    public void refreshHeartbeat(final UUID tenantId, final UUID boardId, final UUID userId) {
        String key = HEARTBEAT_PREFIX + tenantId + ":" + boardId + ":" + userId;
        redisTemplate.opsForValue().set(key, MEMBER_VALUE, HEARTBEAT_TTL);
    }

    /**
     * Checks membership in the database, verifying tenant isolation first.
     *
     * @param tenantId the requesting tenant UUID
     * @param boardId  the board UUID
     * @param userId   the user UUID
     * @return {@code true} if the user has a membership record on a board owned by the tenant
     */
    private boolean lookupMembership(final UUID tenantId, final UUID boardId, final UUID userId) {
        boolean boardOwnedByTenant = boardRepository.findById(boardId)
                .map(board -> board.getTenantId().equals(tenantId))
                .orElse(false);
        if (!boardOwnedByTenant) {
            return false;
        }
        return boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId).isPresent();
    }
}
