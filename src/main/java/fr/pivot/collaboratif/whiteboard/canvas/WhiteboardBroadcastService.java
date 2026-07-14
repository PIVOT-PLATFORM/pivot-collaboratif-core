package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Shared STOMP broadcaster for board-level lifecycle events that originate outside the
 * canvas action pipeline ({@link CanvasActionService}) — currently only the board reset
 * (US08.2.4).
 *
 * <p>Reuses the same destination convention and payload shape ({@link BroadcastCanvasMessage})
 * as {@link CanvasActionService#handle}, so existing STOMP clients subscribed to
 * {@code /topic/whiteboard/{boardId}} handle this message the same way they already handle
 * JOIN/LEAVE/DRAW/CURSOR_MOVE/UNDO.
 */
@Service
public class WhiteboardBroadcastService {

    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the service.
     *
     * @param messagingTemplate STOMP broadcast template
     */
    public WhiteboardBroadcastService(final SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Broadcasts a {@link CanvasEventType#RESET} event to every participant currently
     * subscribed to the board's canvas topic (US08.2.4).
     *
     * <p>The reset itself (deleting the board's persisted DRAW events) has already happened
     * server-side by the time this is called — this only notifies connected clients so they
     * clear their local canvas view in real time.
     *
     * @param boardId the board UUID
     * @param userId  the {@code public.users.id} of the user who triggered the reset
     */
    public void broadcastReset(final UUID boardId, final Long userId) {
        String destination = BOARD_TOPIC_PREFIX + boardId;
        BroadcastCanvasMessage msg = new BroadcastCanvasMessage(
                CanvasEventType.RESET.name(), boardId.toString(), userId.toString(), Map.of());
        messagingTemplate.convertAndSend(destination, msg);
    }
}
