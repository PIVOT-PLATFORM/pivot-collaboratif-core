package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import fr.pivot.collaboratif.whiteboard.canvas.CardType;

import java.util.UUID;

/**
 * Spring application event requesting an asynchronous OpenGraph enrichment pass for one
 * {@link fr.pivot.collaboratif.whiteboard.canvas.Card} (US08.6.5).
 *
 * <p><strong>Integration point for other card-type US's.</strong> This event is the sole,
 * stable hand-off contract between this enricher and any card mutation path that wants a
 * {@code LINK}/{@code TEXT}/{@code LABEL} card checked for a URL to preview. Any Spring bean
 * that creates or updates a card's content — {@code CanvasActionService} (this US, wired for
 * {@code CARD_CREATE}/{@code CARD_UPDATE}) or a future one (US08.6.1/US08.6.2's own TEXT/LABEL
 * mutation path, once implemented) — publishes this event via {@link
 * org.springframework.context.ApplicationEventPublisher#publishEvent(Object)} after its own
 * transaction has done its persistence work; it does not need to depend on, import, or know
 * about {@link OpenGraphEnrichmentListener} or any OpenGraph-specific type at all.
 *
 * <p>Deliberately carries the raw {@code content} and {@link CardType} rather than a URL — URL
 * <em>detection</em> ({@code https?://[^\s<>"']+}, parity spec §3.4) is centralised in {@link
 * CardUrlExtractor} on the consumer side, so every publisher applies the exact same rule and a
 * change to the detection regex never needs to be replicated across multiple mutation paths.
 *
 * <p>Listened to by {@link OpenGraphEnrichmentListener} as a {@code
 * @TransactionalEventListener(phase = AFTER_COMMIT)} — the event must only be published once
 * the publisher's own transaction has (or will) commit the card row this refers to, so that the
 * listener's own {@code UPDATE ... WHERE id = :id} on the {@code meta} column always finds the
 * row. Publishing it via {@code ApplicationEventPublisher} from inside an {@code @Transactional}
 * method (as {@code CanvasActionService} does) satisfies this automatically.
 *
 * @param cardId   the card's UUID
 * @param boardId  the owning board's UUID (defence in depth — every persistence/broadcast this
 *                 event triggers is re-scoped by this id, never {@code cardId} alone)
 * @param tenantId the owning board's tenant id, currently unused by the listener but carried
 *                 for forward compatibility (e.g. per-tenant enrichment metrics/quotas)
 * @param type     the card's typed discriminant at the time of the mutation
 * @param content  the card's new content, to be scanned for a candidate URL
 */
public record CardContentEnrichmentRequestedEvent(
        UUID cardId,
        UUID boardId,
        Long tenantId,
        CardType type,
        String content) {
}
