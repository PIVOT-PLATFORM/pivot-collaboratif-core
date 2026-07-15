package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@link CardContentEnrichmentRequestedEvent} and drives the whole OpenGraph
 * enrichment pipeline (US08.6.5): URL detection, SSRF-guarded fetch, sanitisation, persistence
 * of the {@code meta} JSONB cache, and the {@code card:meta_updated} broadcast.
 *
 * <p>Runs on {@code openGraphExecutor} ({@link OpenGraphAsyncConfig}) strictly <strong>after</strong>
 * the publishing transaction commits ({@code @TransactionalEventListener(phase = AFTER_COMMIT)})
 * — this is what makes the whole pipeline safe to run off-thread: by the time this method
 * executes, the card row the event refers to is guaranteed to be visible to a fresh read, so
 * {@link CardRepository#updateMeta} always finds it (barring a genuine concurrent deletion, which
 * degrades gracefully to a no-op, see below). {@code fallbackExecution = true} keeps this
 * listener working even if a future publisher ever calls it outside a transaction. Because the
 * publishing transaction has, by definition, already ended by the time this method runs on its
 * own async thread, this method opens its <strong>own</strong> transaction ({@code
 * @Transactional(propagation = REQUIRES_NEW)} — otherwise {@link CardRepository}'s {@code
 * @Modifying} queries throw {@code InvalidDataAccessApiUsageException: No active transaction};
 * plain {@code @Transactional} (default {@code REQUIRED}) is rejected outright at startup by
 * Spring for a {@code @TransactionalEventListener} — it must be {@code REQUIRES_NEW} or {@code
 * NOT_SUPPORTED}, never a propagation that could try to join a non-existent transaction).
 *
 * <p>Every failure — blocked SSRF target, DNS/connection failure, timeout, non-2xx status, wrong
 * content-type, too many redirects — is absorbed here silently (parity spec Error AC): logged at
 * debug level, never rethrown, never broadcast as a {@code *:error} frame. The card simply keeps
 * (or keeps not having) a preview.
 */
@Component
class OpenGraphEnrichmentListener {

    private static final Logger LOG = LoggerFactory.getLogger(OpenGraphEnrichmentListener.class);
    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";

    private final OpenGraphFetcher fetcher;
    private final CardRepository cardRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the listener.
     *
     * @param fetcher           performs the SSRF-guarded, capped HTTP fetch and OG tag parsing
     * @param cardRepository    persists the {@code meta} JSONB cache
     * @param messagingTemplate broadcasts {@code card:meta_updated} to the board's STOMP topic
     * @param objectMapper      serialises the sanitised metadata to the JSON stored in
     *                          {@code Card.meta}
     */
    OpenGraphEnrichmentListener(
            final OpenGraphFetcher fetcher,
            final CardRepository cardRepository,
            final SimpMessagingTemplate messagingTemplate,
            final ObjectMapper objectMapper) {
        this.fetcher = fetcher;
        this.cardRepository = cardRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles one enrichment request: detects a candidate URL, fetches and sanitises its
     * OpenGraph metadata, and persists + broadcasts the result — or, if no URL is present
     * anymore, explicitly clears a previously-set preview (parity spec AC4).
     *
     * @param event the enrichment request
     */
    @Async("openGraphExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCardContentEnrichmentRequested(final CardContentEnrichmentRequestedEvent event) {
        try {
            Optional<String> url = CardUrlExtractor.extract(event.type(), event.content());
            if (url.isEmpty()) {
                clearPreviewIfPresent(event);
                return;
            }
            Optional<OpenGraphMeta> meta = fetcher.fetch(url.get());
            meta.ifPresent(m -> persistAndBroadcast(event, m));
        } catch (Exception e) {
            // Absorbed by design (parity spec Error AC) — a failed/blocked fetch must never
            // surface as an exception or a *:error frame; the card keeps meta = null.
            LOG.debug("OpenGraph enrichment absorbed a failure for card {}: {}",
                    event.cardId(), e.toString());
        }
    }

    /**
     * Clears a card's preview when its content no longer contains a URL, but only if it
     * currently has one — avoids a spurious broadcast for the common case of a plain TEXT/LABEL
     * card that never had a link (parity spec AC4 is scoped to a card whose content
     * "contenait une URL et un aperçu").
     *
     * @param event the enrichment request (its content already has no detectable URL)
     */
    private void clearPreviewIfPresent(final CardContentEnrichmentRequestedEvent event) {
        Card card = cardRepository.findById(event.cardId()).orElse(null);
        if (card == null || !card.getBoardId().equals(event.boardId()) || card.getMeta() == null) {
            return;
        }
        int updated = cardRepository.updateMeta(event.cardId(), event.boardId(), null);
        if (updated > 0) {
            broadcastMetaUpdated(event.boardId(), event.cardId(), null);
        }
    }

    /**
     * Persists the sanitised metadata as JSON and broadcasts it to the board's room, unless the
     * card has meanwhile been deleted or moved off this board (0 rows affected — silently
     * skipped, mirroring every other card mutation's "0 rows -> no broadcast" convention).
     *
     * @param event the enrichment request
     * @param meta  the sanitised metadata
     */
    private void persistAndBroadcast(final CardContentEnrichmentRequestedEvent event, final OpenGraphMeta meta) {
        Map<String, Object> metaMap = toMap(meta);
        String json = serialise(metaMap);
        int updated = cardRepository.updateMeta(event.cardId(), event.boardId(), json);
        if (updated > 0) {
            broadcastMetaUpdated(event.boardId(), event.cardId(), metaMap);
        }
    }

    private Map<String, Object> toMap(final OpenGraphMeta meta) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", meta.title());
        map.put("description", meta.description());
        map.put("image", meta.image());
        map.put("siteName", meta.siteName());
        return map;
    }

    private String serialise(final Map<String, Object> meta) {
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            LOG.warn("Could not serialise OpenGraph meta: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Broadcasts {@code card:meta_updated} to every participant on the board's main topic (the
     * whole room, never scoped to a single recipient — parity spec §3.4: everyone sees the
     * preview appear/disappear).
     *
     * @param boardId the board UUID
     * @param cardId  the card UUID
     * @param meta    the sanitised metadata map, or {@code null} to signal "preview cleared"
     */
    private void broadcastMetaUpdated(final UUID boardId, final UUID cardId, final Map<String, Object> meta) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", cardId.toString());
        data.put("meta", meta);
        BroadcastCanvasMessage message = new BroadcastCanvasMessage(
                "card:meta_updated", boardId.toString(), "system", data);
        messagingTemplate.convertAndSend(BOARD_TOPIC_PREFIX + boardId, message);
    }
}
