package fr.pivot.collaboratif.whiteboard.share;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.exception.BoardShareTokenNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.share.dto.ShareBoardRequest;
import fr.pivot.collaboratif.whiteboard.share.dto.ShareBoardResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Business logic for generating and revoking board share invitation tokens.
 *
 * <p>Tokens are generated with a cryptographically secure random source (256 bits).
 * Only the SHA-256 hash of the plain token is persisted — the plain value is returned
 * to the caller once and never stored or logged.
 */
@Service
@Transactional(readOnly = true)
public class BoardShareService {

    /** Default number of days until a share token expires when the caller does not specify. */
    static final int DEFAULT_TTL_DAYS = 7;

    /** Maximum TTL in days that a caller may request. */
    static final int MAX_TTL_DAYS = 30;

    /** Default maximum number of uses when the caller does not specify. */
    static final int DEFAULT_MAX_USES = 1;

    private final BoardRepository boardRepository;
    private final BoardMemberRepository memberRepository;
    private final BoardShareTokenRepository tokenRepository;
    private final String baseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates the service.
     *
     * @param boardRepository  board persistence
     * @param memberRepository board membership persistence
     * @param tokenRepository  share token persistence
     * @param baseUrl          frontend base URL for composing invitation links
     */
    public BoardShareService(
            final BoardRepository boardRepository,
            final BoardMemberRepository memberRepository,
            final BoardShareTokenRepository tokenRepository,
            @Value("${pivot.share.base-url}") final String baseUrl) {
        this.boardRepository = boardRepository;
        this.memberRepository = memberRepository;
        this.tokenRepository = tokenRepository;
        this.baseUrl = baseUrl;
    }

    /**
     * Generates a new share invitation token for the given board.
     *
     * <p>Only the OWNER of a board may generate share tokens. The role must be
     * {@link BoardRole#EDITOR} or {@link BoardRole#VIEWER} — attempting to create
     * an OWNER token throws {@link IllegalArgumentException} (400 INVALID_ROLE).
     *
     * @param boardId   the board to share
     * @param request   share parameters (role, maxUses, ttlDays)
     * @param callerId  the requesting user's UUID
     * @param tenantId  the requesting user's tenant UUID
     * @return a response containing the token ID, share link, role and expiry
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws IllegalArgumentException   if the requested role is OWNER
     */
    @Transactional
    public ShareBoardResponse generateToken(
            final UUID boardId,
            final ShareBoardRequest request,
            final UUID callerId,
            final UUID tenantId) {
        if (request.role() == BoardRole.OWNER) {
            throw new IllegalArgumentException("INVALID_ROLE");
        }
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireOwner(boardId, callerId, board.getOwnerId());

        String plainToken = generateSecureToken();
        String tokenHash = sha256Hex(plainToken);
        int maxUses = request.maxUses() != null ? request.maxUses() : DEFAULT_MAX_USES;
        int ttlDays = request.ttlDays() != null ? request.ttlDays() : DEFAULT_TTL_DAYS;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Math.min(ttlDays, MAX_TTL_DAYS), ChronoUnit.DAYS);

        BoardShareToken token = tokenRepository.save(new BoardShareToken(
                boardId, tokenHash, request.role(), maxUses, expiresAt, callerId, now));
        logAuditEvent("BoardShared", boardId, callerId,
                "role=" + request.role() + " expiresAt=" + expiresAt);
        return ShareBoardResponse.from(token, plainToken, baseUrl);
    }

    /**
     * Revokes an existing share token.
     *
     * <p>Only the OWNER of the board may revoke a token. Attempting to revoke a token that
     * does not exist, belongs to a different board, or has already been revoked results in a
     * {@link BoardShareTokenNotFoundException} (404).
     *
     * @param boardId   the board the token belongs to
     * @param tokenId   the token UUID to revoke
     * @param callerId  the requesting user's UUID
     * @param tenantId  the requesting user's tenant UUID
     * @throws BoardNotFoundException          if the board is inaccessible
     * @throws BoardAccessDeniedException      if the caller is not the OWNER
     * @throws BoardShareTokenNotFoundException if the token is not found or already revoked
     */
    @Transactional
    public void revokeToken(
            final UUID boardId,
            final UUID tokenId,
            final UUID callerId,
            final UUID tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireOwner(boardId, callerId, board.getOwnerId());

        BoardShareToken token = tokenRepository
                .findActiveByIdAndBoardId(tokenId, boardId)
                .orElseThrow(() -> new BoardShareTokenNotFoundException(tokenId));
        token.revoke(Instant.now());
        tokenRepository.save(token);
        logAuditEvent("BoardShareRevoked", boardId, callerId, "tokenId=" + tokenId);
    }

    private void requireOwner(final UUID boardId, final UUID callerId, final UUID ownerId) {
        if (!callerId.equals(ownerId)) {
            throw new BoardAccessDeniedException(boardId);
        }
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
            final UUID actorId,
            final String details) {
        java.util.logging.Logger.getLogger(getClass().getName())
                .info(() -> "AUDIT " + event + " board=" + boardId
                        + " actor=" + actorId + " " + details);
    }
}
