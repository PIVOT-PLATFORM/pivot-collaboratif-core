package fr.pivot.collaboratif.whiteboard.vote;

import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CanvasActionMessage;
import fr.pivot.collaboratif.whiteboard.vote.dto.VoteSessionResponse;
import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for dot-voting STOMP actions (Vote / dot-voting feature, Klaxoon-style workshop
 * facilitation). Sibling of {@code CanvasActionService} — kept as a separate service so the
 * already-large canvas dispatcher does not grow further; the {@code WhiteboardActionController}
 * routes every {@code vote:*} action envelope here.
 *
 * <p><strong>Wire contract</strong> (aligned pixel-for-pixel on the frontend's
 * {@code board.store.ts}). All frames travel as {@code { type, data }} envelopes on the shared
 * action/broadcast channels, exactly like canvas actions.
 *
 * <p>Inbound ({@code /app/whiteboard/{boardId}/action}):
 * <ul>
 *   <li>{@code vote:start} — {@code { boardId, votesPerPerson, timerSeconds, voterIds }}
 *       (OWNER/EDITOR only)</li>
 *   <li>{@code vote:cast} — {@code { sessionId, boardId, cardId }} (any member, within quota)</li>
 *   <li>{@code vote:uncast} — {@code { sessionId, boardId, cardId }} (any member)</li>
 *   <li>{@code vote:stop} — {@code { sessionId, boardId }} (OWNER/EDITOR only)</li>
 *   <li>{@code vote:extend} — {@code { sessionId, boardId, extraSeconds }} (OWNER/EDITOR only)</li>
 * </ul>
 *
 * <p>Outbound ({@code /topic/whiteboard/{boardId}}), {@code data} is the full
 * {@link VoteSessionResponse}:
 * <ul>
 *   <li>{@code vote:session:started} — on start</li>
 *   <li>{@code vote:updated} — on cast, uncast, extend (note: {@code vote:updated}, not
 *       {@code vote:session:updated} — the frontend subscribes to the former)</li>
 *   <li>{@code vote:session:closed} — on stop</li>
 * </ul>
 *
 * <p>Every refusal is silent (no broadcast, no error frame), matching the canvas mutations'
 * posture — a non-manager starting/stopping, a cast past quota, an unknown session/card, a
 * malformed id: all dropped with a DEBUG log. Board membership itself is already enforced upstream
 * by {@code WhiteboardChannelInterceptor} before any frame reaches this service (EN08.1).
 *
 * <p><strong>Anti-oversurvote.</strong> {@code vote:cast}/{@code vote:uncast} load the session via
 * {@link VoteSessionRepository#findForUpdate} (pessimistic row lock), so the count-then-insert
 * quota check is atomic against a concurrent cast from another tab — a Serializable-equivalent
 * guard scoped to the session row (see {@code V4__vote.sql}).
 */
@Service
@Transactional
public class VoteActionService {

    private static final Logger LOG = LoggerFactory.getLogger(VoteActionService.class);

    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";

    /** Inbound action types. */
    private static final String IN_START = "vote:start";
    private static final String IN_CAST = "vote:cast";
    private static final String IN_UNCAST = "vote:uncast";
    private static final String IN_STOP = "vote:stop";
    private static final String IN_EXTEND = "vote:extend";

    /** Outbound broadcast types (exact frontend subscription names). */
    private static final String OUT_STARTED = "vote:session:started";
    private static final String OUT_UPDATED = "vote:updated";
    private static final String OUT_CLOSED = "vote:session:closed";

    /** Upper bound on the per-user quota accepted at {@code vote:start} (defence against abuse). */
    private static final int MAX_VOTES_PER_PERSON = 1000;

    private final SimpMessagingTemplate messagingTemplate;
    private final VoteSessionRepository voteSessionRepository;
    private final VoteRepository voteRepository;
    private final CardRepository cardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param messagingTemplate     STOMP broadcast template
     * @param voteSessionRepository JPA repository for {@link VoteSession}
     * @param voteRepository        JPA repository for {@link Vote}
     * @param cardRepository        JPA repository used to validate a cast card belongs to the board
     * @param boardMemberRepository JPA repository for role lookups (start/stop/extend guard)
     * @param objectMapper          Jackson mapper for serialising the session DTO into the
     *                              broadcast payload map
     */
    public VoteActionService(
            final SimpMessagingTemplate messagingTemplate,
            final VoteSessionRepository voteSessionRepository,
            final VoteRepository voteRepository,
            final CardRepository cardRepository,
            final BoardMemberRepository boardMemberRepository,
            final ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.voteSessionRepository = voteSessionRepository;
        this.voteRepository = voteRepository;
        this.cardRepository = cardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatches an incoming {@code vote:*} action to its handler after resolving the type. An
     * unknown {@code vote:*} type (should not occur — the controller only routes the five known
     * ones) is dropped with a WARN log.
     *
     * @param boardId   the target board UUID (from the STOMP destination path variable)
     * @param message   the incoming action envelope
     * @param principal the authenticated STOMP session principal
     */
    public void handle(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        switch (message.type()) {
            case IN_START -> handleStart(boardId, data, principal);
            case IN_CAST -> handleCast(boardId, data, principal);
            case IN_UNCAST -> handleUncast(boardId, data, principal);
            case IN_STOP -> handleStop(boardId, data, principal);
            case IN_EXTEND -> handleExtend(boardId, data, principal);
            default -> LOG.warn("Unknown vote action type '{}' — dropped board={} user={}",
                    message.type(), boardId, principal.userId());
        }
    }

    /**
     * Handles {@code vote:start}: opens a new session (OWNER/EDITOR only), unless one is already
     * active on the board. Broadcasts {@code vote:session:started} with the fresh (vote-less)
     * session.
     */
    private void handleStart(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        if (!canManage(boardId, principal.userId())) {
            LOG.debug("vote:start refused (not OWNER/EDITOR): user={} board={}", principal.userId(), boardId);
            return;
        }
        if (voteSessionRepository.existsByBoardIdAndStatus(boardId, VoteStatus.ACTIVE)) {
            LOG.debug("vote:start refused (a session is already active): board={}", boardId);
            return;
        }
        int votesPerPerson = (int) toLong(data.get("votesPerPerson"), 0);
        if (votesPerPerson < 1 || votesPerPerson > MAX_VOTES_PER_PERSON) {
            LOG.debug("vote:start refused (invalid votesPerPerson={}): board={}", votesPerPerson, boardId);
            return;
        }
        Integer timerSeconds = null;
        Instant timerEndsAt = null;
        long rawTimer = toLong(data.get("timerSeconds"), 0);
        if (rawTimer > 0) {
            timerSeconds = (int) Math.min(rawTimer, Integer.MAX_VALUE);
            timerEndsAt = Instant.now().plusSeconds(timerSeconds);
        }
        List<String> voterIds = toStringList(data.get("voterIds"));

        VoteSession session = new VoteSession(
                boardId, principal.tenantId(), votesPerPerson, timerSeconds, timerEndsAt, voterIds, Instant.now());
        try {
            voteSessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent start (partial unique index on ACTIVE per board).
            // The other start's session is authoritative — drop silently, do not broadcast twice.
            LOG.debug("vote:start lost active-session race: board={}", boardId);
            return;
        }
        broadcast(boardId, principal, OUT_STARTED, session);
        LOG.info("Vote started: session={} board={} quota={}", session.getId(), boardId, votesPerPerson);
    }

    /**
     * Handles {@code vote:cast}: adds one dot for the caller on a card of this board, provided the
     * session is active and the caller is under quota. Broadcasts {@code vote:updated}.
     */
    private void handleCast(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        VoteSession session = lockActiveSession(boardId, data, principal);
        if (session == null) {
            return;
        }
        UUID cardId = parseUuid(data.get("cardId"));
        if (cardId == null || cardRepository.countByIdInAndBoardId(List.of(cardId), boardId) != 1) {
            LOG.debug("vote:cast refused (missing/foreign card): board={} card={}", boardId, cardId);
            return;
        }
        long held = voteRepository.countBySessionIdAndUserId(session.getId(), principal.userId());
        if (held >= session.getVotesPerPerson()) {
            LOG.debug("vote:cast refused (quota reached: {}/{}): user={} session={}",
                    held, session.getVotesPerPerson(), principal.userId(), session.getId());
            return;
        }
        voteRepository.save(new Vote(session.getId(), cardId, principal.userId(), Instant.now()));
        broadcast(boardId, principal, OUT_UPDATED, session);
        LOG.debug("Vote cast: session={} card={} user={}", session.getId(), cardId, principal.userId());
    }

    /**
     * Handles {@code vote:uncast}: removes exactly one of the caller's dots from a card (the
     * oldest), if any. Broadcasts {@code vote:updated} only when a dot was actually removed.
     */
    private void handleUncast(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        VoteSession session = lockActiveSession(boardId, data, principal);
        if (session == null) {
            return;
        }
        UUID cardId = parseUuid(data.get("cardId"));
        if (cardId == null) {
            return;
        }
        Optional<Vote> vote = voteRepository.findFirstBySessionIdAndCardIdAndUserIdOrderByCreatedAtAsc(
                session.getId(), cardId, principal.userId());
        if (vote.isEmpty()) {
            LOG.debug("vote:uncast no-op (no vote to remove): session={} card={} user={}",
                    session.getId(), cardId, principal.userId());
            return;
        }
        voteRepository.delete(vote.get());
        broadcast(boardId, principal, OUT_UPDATED, session);
        LOG.debug("Vote uncast: session={} card={} user={}", session.getId(), cardId, principal.userId());
    }

    /**
     * Handles {@code vote:stop}: closes the active session (OWNER/EDITOR only). Broadcasts
     * {@code vote:session:closed} with the final tally.
     */
    private void handleStop(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        if (!canManage(boardId, principal.userId())) {
            LOG.debug("vote:stop refused (not OWNER/EDITOR): user={} board={}", principal.userId(), boardId);
            return;
        }
        UUID sessionId = parseUuid(data.get("sessionId"));
        if (sessionId == null) {
            return;
        }
        Optional<VoteSession> found = voteSessionRepository.findForUpdate(sessionId, boardId, principal.tenantId());
        if (found.isEmpty() || found.get().getStatus() != VoteStatus.ACTIVE) {
            LOG.debug("vote:stop no-op (missing, cross-board/tenant, or already closed): session={} board={}",
                    sessionId, boardId);
            return;
        }
        VoteSession session = found.get();
        session.setStatus(VoteStatus.CLOSED);
        session.setClosedAt(Instant.now());
        voteSessionRepository.save(session);
        broadcast(boardId, principal, OUT_CLOSED, session);
        LOG.info("Vote stopped: session={} board={}", session.getId(), boardId);
    }

    /**
     * Handles {@code vote:extend}: pushes the active session's timer out by {@code extraSeconds}
     * (OWNER/EDITOR only). A session with no timer gets one starting now. Broadcasts
     * {@code vote:updated}.
     */
    private void handleExtend(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        if (!canManage(boardId, principal.userId())) {
            LOG.debug("vote:extend refused (not OWNER/EDITOR): user={} board={}", principal.userId(), boardId);
            return;
        }
        UUID sessionId = parseUuid(data.get("sessionId"));
        if (sessionId == null) {
            return;
        }
        long extra = toLong(data.get("extraSeconds"), 0);
        if (extra <= 0) {
            LOG.debug("vote:extend no-op (non-positive extraSeconds={}): session={}", extra, sessionId);
            return;
        }
        Optional<VoteSession> found = voteSessionRepository.findForUpdate(sessionId, boardId, principal.tenantId());
        if (found.isEmpty() || found.get().getStatus() != VoteStatus.ACTIVE) {
            LOG.debug("vote:extend no-op (missing, cross-board/tenant, or closed): session={} board={}",
                    sessionId, boardId);
            return;
        }
        VoteSession session = found.get();
        Instant base = session.getTimerEndsAt() != null ? session.getTimerEndsAt() : Instant.now();
        session.setTimerEndsAt(base.plusSeconds(extra));
        int previous = session.getTimerSeconds() != null ? session.getTimerSeconds() : 0;
        session.setTimerSeconds((int) Math.min((long) previous + extra, Integer.MAX_VALUE));
        voteSessionRepository.save(session);
        broadcast(boardId, principal, OUT_UPDATED, session);
        LOG.debug("Vote extended: session={} board={} extraSeconds={}", session.getId(), boardId, extra);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Loads and pessimistically locks the active session referenced by {@code data.sessionId},
     * scoped to this board and the caller's tenant — the shared entry guard for
     * {@code vote:cast}/{@code vote:uncast}. Returns {@code null} (caller drops) when the id is
     * malformed, unknown, cross-board/tenant, or the session is no longer active.
     *
     * @param boardId   the board UUID
     * @param data      the incoming payload
     * @param principal the emitting principal
     * @return the locked active session, or {@code null} to drop the action
     */
    private VoteSession lockActiveSession(
            final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        UUID sessionId = parseUuid(data.get("sessionId"));
        if (sessionId == null) {
            return null;
        }
        Optional<VoteSession> found = voteSessionRepository.findForUpdate(sessionId, boardId, principal.tenantId());
        if (found.isEmpty() || found.get().getStatus() != VoteStatus.ACTIVE) {
            LOG.debug("vote cast/uncast dropped (missing, cross-board/tenant, or closed): session={} board={}",
                    sessionId, boardId);
            return null;
        }
        return found.get();
    }

    /**
     * Broadcasts the session (with its current tally) to the whole room under {@code wireType},
     * emitter included. The {@link VoteSessionResponse} is converted to a field map so it becomes
     * the {@code data} of the {@link BroadcastCanvasMessage} — the shape the frontend deserialises.
     *
     * @param boardId   the board UUID
     * @param principal the emitting principal
     * @param wireType  the outbound wire type
     * @param session   the session to serialise (its votes are re-read here)
     */
    private void broadcast(
            final UUID boardId, final StompPrincipal principal, final String wireType, final VoteSession session) {
        List<Vote> votes = voteRepository.findAllBySessionIdOrderByCreatedAtAsc(session.getId());
        VoteSessionResponse dto = VoteSessionResponse.of(session, votes);
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = objectMapper.convertValue(dto, Map.class);
        BroadcastCanvasMessage msg = new BroadcastCanvasMessage(
                wireType, boardId.toString(), principal.userId().toString(), dataMap);
        messagingTemplate.convertAndSend(BOARD_TOPIC_PREFIX + boardId, msg);
    }

    /**
     * Returns whether the given user may manage votes on the board (OWNER or EDITOR) — the guard
     * for {@code vote:start}/{@code vote:stop}/{@code vote:extend}. A missing membership defaults
     * to non-manager.
     *
     * @param boardId the board UUID
     * @param userId  the user's {@code public.users.id}
     * @return {@code true} if the user is OWNER or EDITOR
     */
    private boolean canManage(final UUID boardId, final Long userId) {
        return boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId)
                .map(m -> m.getRole() == BoardRole.OWNER || m.getRole() == BoardRole.EDITOR)
                .orElse(false);
    }

    /**
     * Coerces the incoming polymorphic {@code data} to a field-accessible map, or an empty map for
     * a non-map value (mirrors {@code CanvasActionService#asMap}).
     *
     * @param rawData the raw envelope data
     * @return a string-keyed map, or an empty map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object rawData) {
        return rawData instanceof Map<?, ?> ? (Map<String, Object>) rawData : Map.of();
    }

    /**
     * Parses a raw id value into a {@link UUID}, returning {@code null} for a missing or malformed
     * value so the caller can silently drop the action.
     *
     * @param rawId the raw value
     * @return the parsed UUID, or {@code null}
     */
    private static UUID parseUuid(final Object rawId) {
        if (!(rawId instanceof String s)) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Coerces a JSON-deserialised numeric value to a {@code long}, without assuming one boxed type.
     *
     * @param rawValue     the raw value
     * @param defaultValue the fallback when {@code rawValue} is not a number
     * @return the value as a {@code long}, or {@code defaultValue}
     */
    private static long toLong(final Object rawValue, final long defaultValue) {
        return rawValue instanceof Number n ? n.longValue() : defaultValue;
    }

    /**
     * Coerces a JSON-deserialised value to a list of strings (each element's {@code toString()}),
     * or an empty list when it is not a list.
     *
     * @param rawValue the raw value
     * @return a list of strings (never {@code null})
     */
    private static List<String> toStringList(final Object rawValue) {
        if (!(rawValue instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
