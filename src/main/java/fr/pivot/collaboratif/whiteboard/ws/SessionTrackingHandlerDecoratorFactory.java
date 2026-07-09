package fr.pivot.collaboratif.whiteboard.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Decorates the STOMP {@link WebSocketHandler} so that every session open/close at the
 * transport level is mirrored into {@link WhiteboardSessionRegistry}.
 *
 * <p>Registered on {@link org.springframework.web.socket.config.annotation.WebSocketTransportRegistration}
 * in {@code WebSocketConfig#configureWebSocketTransport} — Spring applies every registered
 * {@link WebSocketHandlerDecoratorFactory} when building the handler chain for the STOMP
 * endpoint, so this factory's decorator wraps the actual handler used in production and in
 * every integration test alike.
 */
@Component
public class SessionTrackingHandlerDecoratorFactory implements WebSocketHandlerDecoratorFactory {

    private final WhiteboardSessionRegistry sessionRegistry;

    /**
     * Creates the factory with the registry it feeds.
     *
     * @param sessionRegistry the registry to update on connection open/close
     */
    public SessionTrackingHandlerDecoratorFactory(final WhiteboardSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Wraps {@code handler} with a decorator that registers/unregisters sessions in
     * {@link WhiteboardSessionRegistry} as they open and close.
     *
     * @param handler the handler to decorate
     * @return the decorated handler
     */
    @Override
    public WebSocketHandler decorate(final WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(final WebSocketSession session) throws Exception {
                sessionRegistry.register(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(final WebSocketSession session, final CloseStatus closeStatus)
                    throws Exception {
                sessionRegistry.unregister(session);
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }
}
