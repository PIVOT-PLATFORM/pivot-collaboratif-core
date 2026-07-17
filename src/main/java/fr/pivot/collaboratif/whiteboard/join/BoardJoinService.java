package fr.pivot.collaboratif.whiteboard.join;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardShareTokenExpiredException;
import fr.pivot.collaboratif.exception.BoardShareTokenNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.join.dto.JoinBoardResponse;
import fr.pivot.collaboratif.whiteboard.share.BoardShareToken;
import fr.pivot.collaboratif.whiteboard.share.BoardShareTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for joining a whiteboard board via a share token.
 *
 * <p>The submitted plain token is hashed with SHA-256, looked up in the database,
 * then verified for validity (not revoked, not expired, use count below the limit).
 * On success a {@link BoardMember} record is created and the token's use count is
 * incremented atomically within the same transaction.
 *
 * <p>Security properties maintained by this service:
 * <ul>
 *   <li>The plain token is never stored or logged — only compared against the stored hash.</li>
 *   <li>The hash comparison uses {@link MessageDigest#isEqual} (constant-time) to prevent
 *       timing-based side-channel attacks.</li>
 *   <li>Cross-tenant access is rejected: the joining user's tenant must match the board's
 *       tenant, verified from the caller's resolved {@code tenantId}.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class BoardJoinService {

    private final BoardShareTokenRepository tokenRepository;
    private final BoardRepository boardRepository;
    private final BoardMemberRepository memberRepository;

    /**
     * Creates the service with its required dependencies.
     *
     * @param tokenRepository  share token persistence
     * @param boardRepository  board persistence
     * @param memberRepository board membership persistence
     */
    public BoardJoinService(
            final BoardShareTokenRepository tokenRepository,
            final BoardRepository boardRepository,
            final BoardMemberRepository memberRepository) {
        this.tokenRepository = tokenRepository;
        this.boardRepository = boardRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * Processes a join request for the given plain token.
     *
     * <p>Execution order:
     * <ol>
     *   <li>Hash the submitted token and look it up in the database.</li>
     *   <li>Verify the stored hash with a constant-time comparison (timing-safe).</li>
     *   <li>Check that the token is not revoked.</li>
     *   <li>Check that the token has not expired and use count is below the limit.</li>
     *   <li>Load the board and verify tenant isolation (403 on cross-tenant).</li>
     *   <li>Upsert (US08.2.5): the creator returns OWNER without change; an existing member keeps
     *       their current role (never demoted/promoted, no token use consumed); a new member is
     *       created and the token's use count incremented.</li>
     * </ol>
     *
     * @param plainToken the raw token value from the query parameter
     * @param callerId   the joining user's {@code public.users.id}
     * @param tenantId   the joining user's tenant's {@code public.tenants.id} (from caller's
     *                   session)
     * @return the join response containing board details and redirect URL
     * @throws BoardShareTokenNotFoundException  if the token does not exist (404)
     * @throws BoardShareTokenExpiredException   if the token is expired or quota exhausted (410)
     * @throws BoardAccessDeniedException        if cross-tenant join is attempted (403)
     */
    @Transactional
    public JoinBoardResponse join(
            final String plainToken,
            final Long callerId,
            final Long tenantId) {

        String submittedHash = sha256Hex(plainToken);

        BoardShareToken token = tokenRepository.findByTokenHash(submittedHash)
                .orElseThrow(() -> new BoardShareTokenNotFoundException(null));

        // Constant-time comparison — prevents timing side-channel on hash lookup
        if (!MessageDigest.isEqual(
                token.getTokenHash().getBytes(StandardCharsets.UTF_8),
                submittedHash.getBytes(StandardCharsets.UTF_8))) {
            throw new BoardShareTokenNotFoundException(null);
        }

        if (token.getRevokedAt() != null) {
            throw new BoardShareTokenNotFoundException(null);
        }

        Instant now = Instant.now();
        if (!token.isUsable(now)) {
            throw new BoardShareTokenExpiredException(
                    token.getExpiresAt().isBefore(now)
                            ? "Share token has expired"
                            : "Share token has reached its maximum use count");
        }

        Board board = boardRepository.findById(token.getBoardId())
                .orElseThrow(() -> new BoardShareTokenNotFoundException(null));

        if (!board.getTenantId().equals(tenantId)) {
            throw new BoardAccessDeniedException(board.getId());
        }

        // Upsert semantics (US08.2.5): joining via a link never demotes/promotes anyone.
        // The creator owns the board and has no share row — return OWNER without upsert.
        if (board.getOwnerId().equals(callerId)) {
            logAuditEvent("BoardJoinOwnerNoop", board.getId(), callerId, "role=OWNER");
            return JoinBoardResponse.from(board, BoardRole.OWNER);
        }

        // Already a member → update:{} — keep the existing role, no token use consumed.
        Optional<BoardMember> existing =
                memberRepository.findByIdBoardIdAndIdUserId(board.getId(), callerId);
        if (existing.isPresent()) {
            BoardRole currentRole = existing.get().getRole();
            logAuditEvent("BoardJoinMemberNoop", board.getId(), callerId, "role=" + currentRole);
            return JoinBoardResponse.from(board, currentRole);
        }

        // New member → create the share and consume one token use.
        BoardMemberId memberId = new BoardMemberId(board.getId(), callerId);
        memberRepository.save(new BoardMember(memberId, token.getRole(), now));
        token.incrementUseCount();
        tokenRepository.save(token);

        logAuditEvent("BoardJoined", board.getId(), callerId, "role=" + token.getRole());
        return JoinBoardResponse.from(board, token.getRole());
    }

    private String sha256Hex(final String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void logAuditEvent(
            final String event,
            final UUID boardId,
            final Long actorId,
            final String details) {
        java.util.logging.Logger.getLogger(getClass().getName())
                .info(() -> "AUDIT " + event + " board=" + boardId
                        + " actor=" + actorId + " " + details);
    }
}
