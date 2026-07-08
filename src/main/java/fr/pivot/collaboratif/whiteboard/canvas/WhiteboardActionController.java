package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.CanvasActionMessage;
import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP controller for whiteboard canvas actions (US08.3.1).
 *
 * <p>Handles all client-to-server canvas action messages published on
 * {@code /app/whiteboard/{boardId}/action}. The controller is a thin delegation layer:
 * it resolves the authenticated principal and forwards to {@link CanvasActionService}
 * which owns all business logic (type validation, persistence, broadcasting).
 *
 * <p>Prior to reaching this controller, every SEND frame passes through
 * {@link fr.pivot.collaboratif.whiteboard.ws.WhiteboardChannelInterceptor} which
 * enforces board membership and the 30 msg/s rate limit (EN08.1). This controller
 * therefore only receives authenticated, authorised, non-rate-limited messages.
 *
 * <p>Unknown message types are caught in {@link CanvasActionService#handle} with a
 * WARN log and silent drop (no session closure, no error frame).
 */
@Controller
public class WhiteboardActionController {

    private static final Logger LOG = LoggerFactory.getLogger(WhiteboardActionController.class);

    private final CanvasActionService canvasActionService;

    /**
     * Creates the controller.
     *
     * @param canvasActionService the canvas action service handling all business logic
     */
    public WhiteboardActionController(final CanvasActionService canvasActionService) {
        this.canvasActionService = canvasActionService;
    }

    /**
     * Receives a canvas action from an authenticated STOMP client.
     *
     * <p>The destination is {@code /app/whiteboard/{boardId}/action} (relative to the
     * application prefix {@code /app}). The board UUID is extracted from the destination
     * path via {@link DestinationVariable}.
     *
     * @param boardId   the board UUID extracted from the STOMP destination path
     * @param message   the deserialised canvas action payload
     * @param principal the STOMP session principal (must be a {@link StompPrincipal})
     * @param sessionId the STOMP session ID of the sender, used by {@link CanvasActionService}
     *                  to register/unregister the session in the presence liveness registry
     *                  on JOIN/LEAVE (resolution of #32)
     */
    @MessageMapping("/whiteboard/{boardId}/action")
    public void handleAction(
            @DestinationVariable("boardId") final UUID boardId,
            @Payload final CanvasActionMessage message,
            final Principal principal,
            @Header("simpSessionId") final String sessionId) {
        if (!(principal instanceof StompPrincipal stompPrincipal)) {
            LOG.warn("Received canvas action without StompPrincipal — board={}", boardId);
            return;
        }
        canvasActionService.handle(boardId, message, stompPrincipal, sessionId);
    }
}
