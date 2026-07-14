package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WhiteboardBroadcastService} (US08.2.4 board reset broadcast).
 */
@ExtendWith(MockitoExtension.class)
class WhiteboardBroadcastServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Given a board and a user, when broadcastReset() is called, then a RESET message is sent
     * to that board's canvas topic carrying the correct type/board/user fields.
     */
    @Test
    void broadcastReset_sendsResetMessageToBoardTopic() {
        WhiteboardBroadcastService service = new WhiteboardBroadcastService(messagingTemplate);
        UUID boardId = UUID.randomUUID();
        Long userId = 42L;

        service.broadcastReset(boardId, userId);

        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BroadcastCanvasMessage> payload =
                ArgumentCaptor.forClass(BroadcastCanvasMessage.class);
        verify(messagingTemplate).convertAndSend(destination.capture(), payload.capture());

        assertThat(destination.getValue()).isEqualTo("/topic/whiteboard/" + boardId);
        assertThat(payload.getValue().type()).isEqualTo("RESET");
        assertThat(payload.getValue().boardId()).isEqualTo(boardId.toString());
        assertThat(payload.getValue().userId()).isEqualTo(userId.toString());
    }
}
