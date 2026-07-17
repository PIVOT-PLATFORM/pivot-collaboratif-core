package fr.pivot.collaboratif.whiteboard.importer;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.BoardField;
import fr.pivot.collaboratif.whiteboard.canvas.BoardFieldRepository;
import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CardConnection;
import fr.pivot.collaboratif.whiteboard.canvas.CardConnectionRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardFieldValue;
import fr.pivot.collaboratif.whiteboard.canvas.CardFieldValueRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardType;
import fr.pivot.collaboratif.whiteboard.canvas.FieldType;
import fr.pivot.collaboratif.whiteboard.canvas.Frame;
import fr.pivot.collaboratif.whiteboard.canvas.FrameRepository;
import fr.pivot.collaboratif.whiteboard.canvas.WhiteboardBroadcastService;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardConnectionDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.FieldValueDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.FrameDto;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonRequest.ImportCardRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonRequest.ImportConnectionRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonRequest.ImportFieldRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonRequest.ImportFieldValueRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonRequest.ImportFrameRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonResponse;
import fr.pivot.collaboratif.whiteboard.importer.dto.UndoImportRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.UndoImportResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Business logic for importing a Klaxoon board export into an existing whiteboard, and undoing
 * that import (US08.13.1).
 *
 * <p><strong>Custom fields.</strong> Unlike the maintainer's original scope note for this US
 * (written before F08.10 — {@link BoardField}/{@link CardFieldValue} — actually landed on
 * {@code main}, PR #93/#95), this implementation uses the <em>real</em> relational
 * {@code board_field}/{@code card_field_value} tables, not a JSON blob on {@link Card#getMeta()}.
 * The acceptance criteria text itself only makes sense against a real, independently-addressable
 * {@link BoardField} row: "un nouveau champ est créé avec order = nombre de champs existants" and
 * "les {@code BoardField} créés par l'import ne sont jamais supprimés par l'undo" both describe a
 * durable entity with its own lifecycle distinct from the cards that carry its values — a JSON
 * blob embedded in each card's {@code meta} column has no such independent existence to preserve.
 * No new migration was added: {@code V5__board_fields.sql} already created both tables for
 * US08.10.1/US08.10.2.
 *
 * <p>Tenant and user identity are always supplied by the controller from the resolved {@code
 * RequestPrincipal} (EN08.3) — never from the request body or a path/query parameter.
 * Cross-tenant board access resolves to 404 ({@link BoardNotFoundException}), never 403, to avoid
 * confirming the existence of a resource in another tenant (mirrors {@code BoardService}).
 */
@Service
public class WhiteboardImportService {

    /** Fixed margin (canvas units) added below the board's existing content (parity spec). */
    private static final double COLLISION_MARGIN = 120.0;

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final CardRepository cardRepository;
    private final CardConnectionRepository cardConnectionRepository;
    private final FrameRepository frameRepository;
    private final BoardFieldRepository boardFieldRepository;
    private final CardFieldValueRepository cardFieldValueRepository;
    private final ImportRateLimitService rateLimitService;
    private final WhiteboardBroadcastService broadcastService;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service with all required dependencies.
     *
     * @param boardRepository          repository for board persistence (access/role resolution)
     * @param boardMemberRepository    repository for board membership persistence (role resolution)
     * @param cardRepository           repository for durable card state
     * @param cardConnectionRepository repository for durable connector state
     * @param frameRepository          repository for durable frame state
     * @param boardFieldRepository     repository for custom board field definitions (F08.10)
     * @param cardFieldValueRepository repository for per-card custom field values (F08.10)
     * @param rateLimitService         Redis-backed per-board import rate limiter
     * @param broadcastService         STOMP broadcaster for {@code board:imported}/
     *                                 {@code board:import-undone}
     * @param objectMapper             Jackson mapper used to serialise a SELECT field's
     *                                 {@code options} array to its persisted JSON string form
     */
    public WhiteboardImportService(
            final BoardRepository boardRepository,
            final BoardMemberRepository boardMemberRepository,
            final CardRepository cardRepository,
            final CardConnectionRepository cardConnectionRepository,
            final FrameRepository frameRepository,
            final BoardFieldRepository boardFieldRepository,
            final CardFieldValueRepository cardFieldValueRepository,
            final ImportRateLimitService rateLimitService,
            final WhiteboardBroadcastService broadcastService,
            final ObjectMapper objectMapper) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.cardRepository = cardRepository;
        this.cardConnectionRepository = cardConnectionRepository;
        this.frameRepository = frameRepository;
        this.boardFieldRepository = boardFieldRepository;
        this.cardFieldValueRepository = cardFieldValueRepository;
        this.rateLimitService = rateLimitService;
        this.broadcastService = broadcastService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Import
    // -------------------------------------------------------------------------

    /**
     * Imports a Klaxoon export into {@code boardId}: persists cards, connectors, frames, and
     * custom fields in one transaction, applies the anti-collision Y offset, remaps connector
     * endpoints through a klxId → server-id map, and broadcasts the full created objects on
     * {@code board:imported}.
     *
     * @param boardId  the target board UUID
     * @param request  the validated import payload
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return the created counts and id lists (the source of truth for a later undo)
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is a VIEWER
     * @throws fr.pivot.collaboratif.exception.TooManyRequestsException if the board's import
     *                                    quota (5/minute) is exhausted
     */
    @Transactional
    public ImportKlaxoonResponse importKlaxoon(
            final UUID boardId,
            final ImportKlaxoonRequest request,
            final Long userId,
            final Long tenantId) {
        Board board = requireEditableBoard(boardId, userId, tenantId);
        rateLimitService.checkAndIncrement(boardId);

        List<ImportCardRequest> cardReqs = orEmpty(request.cards());
        List<ImportFrameRequest> frameReqs = orEmpty(request.frames());
        List<ImportConnectionRequest> connectionReqs = orEmpty(request.connections());
        List<ImportFieldRequest> fieldReqs = orEmpty(request.fields());

        double offsetY = computeOffsetY(boardId, tenantId, cardReqs, frameReqs);
        Instant now = Instant.now();

        Map<String, BoardField> fieldsByLowerName = loadFieldCache(boardId);
        AtomicInteger nextOrder = new AtomicInteger(fieldsByLowerName.size());
        for (ImportFieldRequest fieldReq : fieldReqs) {
            resolveOrCreateField(
                    boardId, tenantId, fieldReq.name(), FieldType.valueOf(fieldReq.type()),
                    serializeOptions(fieldReq.options()), fieldsByLowerName, nextOrder);
        }

        Map<String, UUID> idMap = new HashMap<>();
        Map<String, UUID> groupIdMap = new HashMap<>();
        List<Card> persistedCards = new ArrayList<>(cardReqs.size());
        List<CardFieldValue> fieldValuesToSave = new ArrayList<>();

        for (ImportCardRequest cardReq : cardReqs) {
            Card card = new Card(
                    boardId, tenantId, CardType.valueOf(cardReq.type()),
                    cardReq.content() == null ? "" : cardReq.content(),
                    cardReq.posX(), cardReq.posY() + offsetY, now);
            card.setWidth(cardReq.width());
            card.setHeight(cardReq.height());
            if (cardReq.color() != null) {
                card.setColor(cardReq.color());
            }
            card.setLocked(cardReq.locked());
            card.setLayer(cardReq.zIndex());
            if (cardReq.groupKey() != null && !cardReq.groupKey().isBlank()) {
                card.setGroupId(groupIdMap.computeIfAbsent(cardReq.groupKey(), k -> UUID.randomUUID()));
            }
            Card saved = cardRepository.save(card);
            idMap.put(cardReq.klxId(), saved.getId());
            persistedCards.add(saved);

            if (cardReq.fieldValues() != null) {
                collectFieldValues(
                        boardId, tenantId, saved.getId(), cardReq.fieldValues(),
                        fieldsByLowerName, nextOrder, fieldValuesToSave);
            }
        }

        List<CardFieldValue> savedFieldValues = fieldValuesToSave.isEmpty()
                ? List.of() : cardFieldValueRepository.saveAll(fieldValuesToSave);
        Map<UUID, List<FieldValueDto>> fieldValuesByCard = groupByCard(savedFieldValues);

        List<Frame> persistedFrames = new ArrayList<>(frameReqs.size());
        for (ImportFrameRequest frameReq : frameReqs) {
            Frame frame = new Frame(boardId, tenantId, frameReq.posX(), frameReq.posY() + offsetY, now);
            frame.setWidth(frameReq.width());
            frame.setHeight(frameReq.height());
            frame.setTitle(frameReq.title() == null ? "" : frameReq.title());
            persistedFrames.add(frameRepository.save(frame));
        }

        List<CardConnection> persistedConnections = new ArrayList<>(connectionReqs.size());
        for (ImportConnectionRequest connectionReq : connectionReqs) {
            UUID fromId = idMap.get(connectionReq.fromKlxId());
            UUID toId = idMap.get(connectionReq.toKlxId());
            if (fromId == null || toId == null) {
                // Orphan connector: at least one endpoint was not part of this import's card set
                // (or referenced a klxId absent from idMap) — silently dropped per acceptance
                // criterion, never an error.
                continue;
            }
            CardConnection connection = new CardConnection(boardId, tenantId, fromId, toId, now);
            if (connectionReq.shape() != null) {
                connection.setShape(connectionReq.shape());
            }
            if (connectionReq.color() != null) {
                connection.setColor(connectionReq.color());
            }
            if (connectionReq.arrow() != null) {
                connection.setArrow(connectionReq.arrow());
            }
            if (connectionReq.label() != null) {
                connection.setLabel(connectionReq.label());
            }
            if (connectionReq.width() != null) {
                connection.setWidth(connectionReq.width());
            }
            connection.setDashed(connectionReq.dashed());
            persistedConnections.add(cardConnectionRepository.save(connection));
        }

        board.setUpdatedAt(Instant.now());
        boardRepository.save(board);

        List<CardDto> cardDtos = persistedCards.stream()
                .map(c -> toCardDto(c, fieldValuesByCard.getOrDefault(c.getId(), List.of())))
                .toList();
        List<CardConnectionDto> connectionDtos = persistedConnections.stream()
                .map(this::toConnectionDto)
                .toList();
        List<FrameDto> frameDtos = persistedFrames.stream().map(this::toFrameDto).toList();

        broadcastService.broadcastImported(boardId, userId, cardDtos, connectionDtos, frameDtos);
        // TODO(F08.x / ADR-025): publish collaboratif.board.imported on the ActiveMQ event bus
        // once a publisher exists on this repo (CLAUDE.md, "Temps réel": the EN07.3 relay to
        // /topic/collaboratif.* is wired but nothing publishes to it yet) — deliberate scope
        // deferral, not an oversight; notify() stays unemitted too (parity, US "Hors périmètre" §6
        // constat 14).

        return new ImportKlaxoonResponse(
                cardDtos.size(), connectionDtos.size(), frameDtos.size(),
                idsOf(persistedCards, Card::getId),
                idsOf(persistedConnections, CardConnection::getId),
                idsOf(persistedFrames, Frame::getId));
    }

    // -------------------------------------------------------------------------
    // Undo
    // -------------------------------------------------------------------------

    /**
     * Undoes a prior import: deletes the given cards/connectors/frames in one transaction,
     * strictly scoped to {@code boardId}, then broadcasts {@code board:import-undone} with the
     * <strong>requested</strong> ids. {@link BoardField}s created by the import are never touched.
     *
     * <p>Cards are deleted before the explicit connector delete so that connectors already removed
     * by the {@code ON DELETE CASCADE} of a deleted endpoint card are — legitimately — not
     * double-counted as an error by the subsequent {@code connectionIds} delete (acceptance
     * criterion).
     *
     * @param boardId  the target board UUID
     * @param request  the id lists to delete, capped and validated at the controller layer
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return the actually-deleted counts per category
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is a VIEWER
     * @throws IllegalArgumentException   if any supplied id is not a syntactically valid UUID
     */
    @Transactional
    public UndoImportResponse undoImport(
            final UUID boardId,
            final UndoImportRequest request,
            final Long userId,
            final Long tenantId) {
        requireEditableBoard(boardId, userId, tenantId);

        List<UUID> cardIds = parseUuids(request.cardIds());
        List<UUID> connectionIds = parseUuids(request.connectionIds());
        List<UUID> frameIds = parseUuids(request.frameIds());

        int deletedCards = cardIds.isEmpty()
                ? 0 : cardRepository.deleteAllByIdInAndBoardId(cardIds, boardId);
        int deletedConnections = connectionIds.isEmpty()
                ? 0 : cardConnectionRepository.deleteAllByIdInAndBoardId(connectionIds, boardId);
        int deletedFrames = frameIds.isEmpty()
                ? 0 : frameRepository.deleteAllByIdInAndBoardId(frameIds, boardId);

        broadcastService.broadcastImportUndone(
                boardId, userId, request.cardIds(), request.connectionIds(), request.frameIds());

        return new UndoImportResponse(deletedCards, deletedConnections, deletedFrames);
    }

    // -------------------------------------------------------------------------
    // Helpers — access control
    // -------------------------------------------------------------------------

    /**
     * Loads a non-trashed, tenant-scoped board and asserts the caller holds OWNER or EDITOR —
     * import and undo are refused to a VIEWER (acceptance criterion).
     *
     * @param boardId  the board UUID
     * @param userId   the caller's {@code public.users.id}
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the board
     * @throws BoardNotFoundException     if not found, trashed, cross-tenant, or the caller is not
     *                                    a member (anti-enumeration 404)
     * @throws BoardAccessDeniedException if the caller is a VIEWER
     */
    private Board requireEditableBoard(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        BoardRole role = resolveRole(boardId, userId, board.getOwnerId());
        if (role != BoardRole.OWNER && role != BoardRole.EDITOR) {
            throw new BoardAccessDeniedException(boardId);
        }
        return board;
    }

    /**
     * Resolves the caller's role on a board.
     *
     * @param boardId the board UUID
     * @param userId  the caller's {@code public.users.id}
     * @param ownerId the board's owner's {@code public.users.id}
     * @return the caller's role
     * @throws BoardNotFoundException if the caller is not a member or owner of the board
     */
    private BoardRole resolveRole(final UUID boardId, final Long userId, final Long ownerId) {
        if (userId.equals(ownerId)) {
            return BoardRole.OWNER;
        }
        return boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId)
                .map(BoardMember::getRole)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
    }

    // -------------------------------------------------------------------------
    // Helpers — anti-collision offset
    // -------------------------------------------------------------------------

    /**
     * Computes the Y offset applied to every imported card/frame (acceptance criteria: worked
     * example {@code bottom=136, importTop=40 → offsetY=216}).
     *
     * <p>{@code offsetY = round(bottom + 120 - importTop)} when the board already has content
     * (any card or frame) <strong>and</strong> the import itself is non-empty (any card or
     * frame); {@code 0} otherwise (empty board, or an import carrying only connectors/fields).
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param cards    the import's card requests
     * @param frames   the import's frame requests
     * @return the Y offset to add to every imported card/frame's {@code posY}
     */
    private double computeOffsetY(
            final UUID boardId,
            final Long tenantId,
            final List<ImportCardRequest> cards,
            final List<ImportFrameRequest> frames) {
        boolean importNonEmpty = !cards.isEmpty() || !frames.isEmpty();
        if (!importNonEmpty) {
            return 0.0;
        }
        Optional<Double> cardBottom = cardRepository.findMaxBottom(boardId, tenantId);
        Optional<Double> frameBottom = frameRepository.findMaxBottom(boardId, tenantId);
        if (cardBottom.isEmpty() && frameBottom.isEmpty()) {
            return 0.0;
        }
        double bottom = Math.max(
                cardBottom.orElse(Double.NEGATIVE_INFINITY),
                frameBottom.orElse(Double.NEGATIVE_INFINITY));
        double importTop = Math.min(
                cards.stream().mapToDouble(ImportCardRequest::posY).min().orElse(Double.POSITIVE_INFINITY),
                frames.stream().mapToDouble(ImportFrameRequest::posY).min().orElse(Double.POSITIVE_INFINITY));
        return Math.round(bottom + COLLISION_MARGIN - importTop);
    }

    // -------------------------------------------------------------------------
    // Helpers — custom fields (F08.10 BoardField / CardFieldValue)
    // -------------------------------------------------------------------------

    /**
     * Loads every existing {@link BoardField} of the board, keyed by lower-cased name, for the
     * case-insensitive reuse-or-create lookup driving the import's field resolution.
     *
     * @param boardId the board UUID
     * @return a mutable map of the board's existing fields, keyed by {@code name.toLowerCase()}
     */
    private Map<String, BoardField> loadFieldCache(final UUID boardId) {
        Map<String, BoardField> byLowerName = new HashMap<>();
        for (BoardField field : boardFieldRepository.findAllByBoardIdOrderByOrderAscCreatedAtAsc(boardId)) {
            byLowerName.put(field.getName().toLowerCase(Locale.ROOT), field);
        }
        return byLowerName;
    }

    /**
     * Resolves a field by case-insensitive name against the running cache, creating and
     * persisting a new {@link BoardField} — with {@code order} set to the current count of the
     * board's fields — if none matches (acceptance criterion).
     *
     * @param boardId          the board UUID
     * @param tenantId         the tenant's {@code public.tenants.id}
     * @param name             the field name (matched via {@code toLowerCase()})
     * @param typeIfCreated    the type to persist if a new field must be created (ignored on reuse
     *                         — an existing field's type is never changed)
     * @param optionsIfCreated the persisted {@code options} JSON string if a new field must be
     *                         created, or {@code null}
     * @param cache            the running name → field cache, mutated in place
     * @param nextOrder        the running "next order value" counter, incremented on creation
     * @return the resolved (existing or freshly created) field
     */
    private BoardField resolveOrCreateField(
            final UUID boardId,
            final Long tenantId,
            final String name,
            final FieldType typeIfCreated,
            final String optionsIfCreated,
            final Map<String, BoardField> cache,
            final AtomicInteger nextOrder) {
        String key = name.toLowerCase(Locale.ROOT);
        BoardField existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        BoardField created = new BoardField(
                boardId, tenantId, name, null, typeIfCreated, optionsIfCreated,
                nextOrder.getAndIncrement(), Instant.now());
        created = boardFieldRepository.save(created);
        cache.put(key, created);
        return created;
    }

    /**
     * Resolves (or creates) a field for each of a card's {@code fieldValues} entries and stages a
     * {@link CardFieldValue} for each, deduplicated by field id (last value wins) so a payload
     * naming the same field twice for one card never trips the {@code (card_id, field_id)} unique
     * constraint.
     *
     * @param boardId    the board UUID
     * @param tenantId   the tenant's {@code public.tenants.id}
     * @param cardId     the already-persisted card's server id
     * @param values     the card's requested field values
     * @param cache      the running name → field cache
     * @param nextOrder  the running "next order value" counter
     * @param outStaged  the list every resolved {@link CardFieldValue} is appended to for a later
     *                   batch {@code saveAll}
     */
    private void collectFieldValues(
            final UUID boardId,
            final Long tenantId,
            final UUID cardId,
            final List<ImportFieldValueRequest> values,
            final Map<String, BoardField> cache,
            final AtomicInteger nextOrder,
            final List<CardFieldValue> outStaged) {
        Map<UUID, CardFieldValue> dedupeByFieldId = new LinkedHashMap<>();
        for (ImportFieldValueRequest value : values) {
            BoardField field = resolveOrCreateField(
                    boardId, tenantId, value.field(), FieldType.TEXT, null, cache, nextOrder);
            dedupeByFieldId.put(field.getId(), new CardFieldValue(cardId, field.getId(), value.value()));
        }
        outStaged.addAll(dedupeByFieldId.values());
    }

    /**
     * Serialises a SELECT field's {@code options} list to its persisted JSON array string.
     *
     * @param options the raw options list, or {@code null}
     * @return the JSON array string, or {@code null} if {@code options} is {@code null} or empty
     */
    private String serializeOptions(final List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(options);
    }

    /**
     * Groups a flat list of saved field values by their owning card id, converting each to its
     * wire {@link FieldValueDto}, for embedding into each imported card's {@code CardDto}.
     *
     * @param savedFieldValues the persisted field values (DB-assigned ids)
     * @return the field values grouped by card id
     */
    private Map<UUID, List<FieldValueDto>> groupByCard(final List<CardFieldValue> savedFieldValues) {
        Map<UUID, List<FieldValueDto>> byCard = new LinkedHashMap<>();
        for (CardFieldValue value : savedFieldValues) {
            byCard.computeIfAbsent(value.getCardId(), k -> new ArrayList<>()).add(FieldValueDto.of(value));
        }
        return byCard;
    }

    // -------------------------------------------------------------------------
    // Helpers — DTO mapping and misc
    // -------------------------------------------------------------------------

    private CardDto toCardDto(final Card card, final List<FieldValueDto> fieldValues) {
        return CardDto.of(
                card.getId(), card.getType().name(), card.getContent(), null,
                card.getPosX(), card.getPosY(), card.getWidth(), card.getHeight(), card.getColor(),
                card.getGroupId(), card.getGroupColor(), card.isLocked(), card.getLayer(), fieldValues);
    }

    private CardConnectionDto toConnectionDto(final CardConnection connection) {
        return CardConnectionDto.of(
                connection.getId(), connection.getFromId(), connection.getToId(), connection.getLabel(),
                connection.getColor(), connection.getShape(), connection.getArrow(),
                connection.isDashed(), connection.getWidth(),
                connection.getLineStyle(), connection.getStartCap(), connection.getEndCap());
    }

    private FrameDto toFrameDto(final Frame frame) {
        return FrameDto.of(
                frame.getId(), frame.getBoardId(), frame.getTitle(), frame.getPosX(), frame.getPosY(),
                frame.getWidth(), frame.getHeight(), frame.getColor(), frame.isActive(), frame.getLayer());
    }

    private <T> List<String> idsOf(final List<T> entities, final java.util.function.Function<T, UUID> idFn) {
        return entities.stream().map(idFn).map(UUID::toString).toList();
    }

    private List<UUID> parseUuids(final List<String> raw) {
        return raw.stream().map(UUID::fromString).toList();
    }

    private <T> List<T> orEmpty(final List<T> list) {
        return list == null ? List.of() : list;
    }
}
