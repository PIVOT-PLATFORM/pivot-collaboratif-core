package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantsUpdatePayload;
import fr.pivot.collaboratif.whiteboard.canvas.dto.PresenceUserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParticipantsBroadcastService} (US08.5.1) — the single shared
 * PARTICIPANTS_UPDATE broadcaster used by both {@code CanvasActionService} (explicit
 * JOIN/LEAVE) and {@link fr.pivot.collaboratif.whiteboard.ws.WhiteboardPresenceRegistry}
 * (disconnect cleanup).
 */
@ExtendWith(MockitoExtension.class)
class ParticipantsBroadcastServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ParticipantMetaStore participantMetaStore;

    private ParticipantsBroadcastService broadcastService;

    private static final Long TENANT_ID = 100L;
    private static final UUID BOARD_ID = UUID.randomUUID();

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        broadcastService = new ParticipantsBroadcastService(messagingTemplate, participantMetaStore);
    }

    /**
     * Given a board with two participants stored,
     * when broadcast() is called,
     * then a PARTICIPANTS_UPDATE with both participants is sent to the board's presence topic.
     */
    @Test
    void broadcast_sends_current_participant_list_to_presence_topic() {
        ParticipantInfo alice = new ParticipantInfo(UUID.randomUUID().toString(), "Alice", null, "#E91E63", "OWNER");
        ParticipantInfo bob = new ParticipantInfo(UUID.randomUUID().toString(), "Bob", null, "#9C27B0", "EDITOR");
        when(participantMetaStore.getAll(TENANT_ID, BOARD_ID)).thenReturn(List.of(alice, bob));

        broadcastService.broadcast(TENANT_ID, BOARD_ID);

        ArgumentCaptor<ParticipantsUpdatePayload> payloadCaptor = ArgumentCaptor.forClass(ParticipantsUpdatePayload.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/whiteboard/" + BOARD_ID + "/presence"), (Object) payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().participants()).containsExactly(alice, bob);
    }

    /**
     * Given a board with no participants stored,
     * when broadcast() is called,
     * then a PARTICIPANTS_UPDATE with an empty list is still sent (not skipped).
     */
    @Test
    void broadcast_sends_empty_list_when_board_has_no_participants() {
        when(participantMetaStore.getAll(TENANT_ID, BOARD_ID)).thenReturn(List.of());

        broadcastService.broadcast(TENANT_ID, BOARD_ID);

        ArgumentCaptor<ParticipantsUpdatePayload> payloadCaptor = ArgumentCaptor.forClass(ParticipantsUpdatePayload.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/whiteboard/" + BOARD_ID + "/presence"), (Object) payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().participants()).isEmpty();
    }

    /**
     * Given a board with a participant stored,
     * when broadcast() is called,
     * then a {@code board:presence} {@link BroadcastCanvasMessage} carrying the
     * {@link PresenceUserDto} projection ({@code id}/{@code name}/{@code avatar}) is also sent to
     * the board's <strong>main</strong> topic — the shape the frontend's
     * {@code this.on<PresenceUser[]>('board:presence', …)} handler consumes (P4).
     */
    @Test
    void broadcast_also_sends_board_presence_projection_to_main_topic() {
        String userId = UUID.randomUUID().toString();
        ParticipantInfo alice = new ParticipantInfo(userId, "Alice", "http://x/a.png", "#E91E63", "OWNER");
        when(participantMetaStore.getAll(TENANT_ID, BOARD_ID)).thenReturn(List.of(alice));

        broadcastService.broadcast(TENANT_ID, BOARD_ID);

        ArgumentCaptor<BroadcastCanvasMessage> msgCaptor = ArgumentCaptor.forClass(BroadcastCanvasMessage.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/whiteboard/" + BOARD_ID), (Object) msgCaptor.capture());
        BroadcastCanvasMessage msg = msgCaptor.getValue();
        assertThat(msg.type()).isEqualTo("board:presence");
        assertThat(msg.data()).isEqualTo(List.of(new PresenceUserDto(userId, "Alice", "http://x/a.png")));
    }
}
