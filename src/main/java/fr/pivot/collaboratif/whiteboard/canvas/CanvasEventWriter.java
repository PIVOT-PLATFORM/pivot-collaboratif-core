package fr.pivot.collaboratif.whiteboard.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Persists {@code DRAW} canvas events off the STOMP handler thread (US08.6 perf hardening).
 *
 * <p>DRAW is the highest-frequency canvas mutation, yet its persisted {@link CanvasEvent} rows are
 * not read on any production path today (replay-on-join is unwired — see {@code CanvasEventRepository}).
 * Writing them synchronously inside the STOMP dispatch transaction added a Postgres INSERT (+ WAL,
 * + JSONB parse) and pinned a pooled JDBC connection across the broadcast on the hot path. Moving
 * the write here — a {@code @Async} call on the bounded {@code canvasEventExecutor} pool
 * ({@link CanvasEventAsyncConfig}) — takes it off the STOMP thread and out of the handler's
 * transaction: the {@link CanvasEventRepository#save} runs in its own short transaction on a pool
 * thread, so the client round-trip no longer waits on the DB write.
 *
 * <p>Fire-and-forget by contract: a persistence failure is logged, never surfaced to the drawing
 * client (the authoritative board state lives in the {@code Card}/{@code Frame} tables and the live
 * broadcast, not in this append-only log).
 */
@Component
class CanvasEventWriter {

    private static final Logger LOG = LoggerFactory.getLogger(CanvasEventWriter.class);

    private final CanvasEventRepository canvasEventRepository;

    CanvasEventWriter(final CanvasEventRepository canvasEventRepository) {
        this.canvasEventRepository = canvasEventRepository;
    }

    /**
     * Persists a fully-built {@link CanvasEvent} asynchronously on the {@code canvasEventExecutor}
     * pool. The event carries its own id and creation timestamp (set by the caller at receive time,
     * preserving Last-Write-Wins ordering), so running later on another thread does not reorder it.
     *
     * @param event the DRAW event to persist (a new, detached entity)
     */
    @Async("canvasEventExecutor")
    public void persist(final CanvasEvent event) {
        try {
            canvasEventRepository.save(event);
            LOG.debug("Canvas DRAW persisted async: eventId={} board={}", event.getId(), event.getBoardId());
        } catch (RuntimeException ex) {
            LOG.warn("Async DRAW persistence failed (dropped): eventId={} board={}",
                    event.getId(), event.getBoardId(), ex);
        }
    }
}
