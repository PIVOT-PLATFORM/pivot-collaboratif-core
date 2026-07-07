package fr.pivot.collaboratif.whiteboard.ws;

import java.security.Principal;
import java.util.UUID;

/**
 * Authenticated caller identity carried through a STOMP WebSocket session.
 *
 * <p>Populated during the HTTP handshake by {@link StompHandshakeHandler} and
 * propagated by Spring as the session {@link Principal} for every subsequent STOMP
 * frame. Provides the {@code userId} and {@code tenantId} required for board
 * membership checks in {@link WhiteboardChannelInterceptor}.
 *
 * <p>TODO: replace header-based identity with opaque-token validation once
 * {@code fr.pivot:pivot-core-starter} is published (EN17).
 */
public record StompPrincipal(UUID userId, UUID tenantId) implements Principal {

    /**
     * Returns the string representation of the user UUID, used by Spring as the
     * canonical user name for {@link org.springframework.messaging.simp.SimpMessagingTemplate#convertAndSendToUser}.
     *
     * @return {@code userId.toString()}
     */
    @Override
    public String getName() {
        return userId.toString();
    }
}
