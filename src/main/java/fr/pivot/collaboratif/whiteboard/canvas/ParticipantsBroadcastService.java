package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantsUpdatePayload;
import fr.pivot.collaboratif.whiteboard.canvas.dto.PresenceUserDto;
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
     * Broadcasts the current full participant list on every JOIN/LEAVE (and on the last-session
     * WebSocket disconnect, via {@link fr.pivot.collaboratif.whiteboard.ws.WhiteboardPresenceRegistry}),
     * on <strong>two</strong> topics that two different frontend consumers subscribe to:
     * <ul>
     *   <li>the {@code /presence} sub-topic — {@link ParticipantsUpdatePayload} (the fuller
     *       {@code userId}/{@code displayName}/{@code color}/{@code role} shape, US08.5.1);</li>
     *   <li>the <strong>main</strong> topic {@code /topic/whiteboard/{boardId}} — a
     *       {@code board:presence} {@link BroadcastCanvasMessage} carrying a {@link PresenceUserDto}
     *       array, which the store's {@code this.on<PresenceUser[]>('board:presence', …)} handler
     *       drives the presence panel from. Emitting it here (rather than only in the JOIN handler)
     *       keeps a single source of truth so LEAVE and last-session disconnect also refresh
     *       {@code board:presence}.</li>
     * </ul>
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param boardId  the board UUID
     */
    public void broadcast(final Long tenantId, final UUID boardId) {
        List<ParticipantInfo> participants = participantMetaStore.getAll(tenantId, boardId);

        String presenceSubTopic = BOARD_TOPIC_PREFIX + boardId + PRESENCE_SUFFIX;
        messagingTemplate.convertAndSend(presenceSubTopic, new ParticipantsUpdatePayload(participants));

        List<PresenceUserDto> presenceUsers = participants.stream().map(PresenceUserDto::from).toList();
        String mainTopic = BOARD_TOPIC_PREFIX + boardId;
        messagingTemplate.convertAndSend(
                mainTopic, new BroadcastCanvasMessage("board:presence", boardId.toString(), "", presenceUsers));

        LOG.debug("Presence broadcast: {} participant(s) on board={}", participants.size(), boardId);
    }
}
