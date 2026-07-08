package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantsUpdatePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Broadcasts the current participant list to a board's presence topic.
 *
 * <p>Single source of truth for {@code PARTICIPANTS_UPDATE} emission (US08.5.1), shared
 * between {@link CanvasActionService} (explicit JOIN/LEAVE application messages) and
 * {@link fr.pivot.collaboratif.whiteboard.ws.WhiteboardPresenceRegistry} (implicit cleanup
 * on WebSocket disconnect without an explicit LEAVE, e.g. a client crash). Extracting this
 * as a shared component avoids the two systems independently broadcasting different payload
 * shapes to the same topic, which was the collision resolved by this US
 * (see pivot-collaboratif-core#32).
 */
@Component
public class ParticipantsBroadcastService {

    private static final Logger LOG = LoggerFactory.getLogger(ParticipantsBroadcastService.class);
    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";
    private static final String PRESENCE_SUFFIX = "/presence";

    private final SimpMessagingTemplate messagingTemplate;
    private final ParticipantMetaStore participantMetaStore;

    /**
     * Creates the broadcaster.
     *
     * @param messagingTemplate    STOMP broadcast template
     * @param participantMetaStore store holding the participant metadata to broadcast
     */
    public ParticipantsBroadcastService(
            final SimpMessagingTemplate messagingTemplate,
            final ParticipantMetaStore participantMetaStore) {
        this.messagingTemplate = messagingTemplate;
        this.participantMetaStore = participantMetaStore;
    }

    /**
     * Broadcasts a {@code PARTICIPANTS_UPDATE} with the current full participant list to
     * the board's presence topic.
     *
     * @param tenantId the tenant UUID
     * @param boardId  the board UUID
     */
    public void broadcast(final UUID tenantId, final UUID boardId) {
        List<ParticipantInfo> participants = participantMetaStore.getAll(tenantId, boardId);
        String destination = BOARD_TOPIC_PREFIX + boardId + PRESENCE_SUFFIX;
        messagingTemplate.convertAndSend(destination, new ParticipantsUpdatePayload(participants));
        LOG.debug("PARTICIPANTS_UPDATE: {} participant(s) on board={}", participants.size(), boardId);
    }
}
