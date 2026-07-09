package fr.pivot.collaboratif.whiteboard.ws;

import java.security.Principal;

/**
 * Authenticated caller identity carried through a STOMP WebSocket session.
 *
 * <p>Populated during the HTTP handshake by {@link StompHandshakeHandler} and
 * propagated by Spring as the session {@link Principal} for every subsequent STOMP
 * frame. Provides the {@code userId} and {@code tenantId} required for board
 * membership checks in {@link WhiteboardChannelInterceptor}.
 *
 * <p>Carries the real platform identities ({@code public.users.id}/{@code public.tenants.id})
 * resolved from the {@code Authorization: Bearer} handshake header by {@link
 * StompHandshakeInterceptor} via {@code fr.pivot.core.auth.AuthenticatedPrincipalResolver}
 * (EN08.3, ADR-022) — see that class's JavaDoc for the handshake-time token convention.
 */
public record StompPrincipal(Long userId, Long tenantId) implements Principal {

    /**
     * Returns the string representation of the user id, used by Spring as the
     * canonical user name for {@link org.springframework.messaging.simp.SimpMessagingTemplate#convertAndSendToUser}.
     *
     * @return {@code userId.toString()}
     */
    @Override
    public String getName() {
        return userId.toString();
    }
}
