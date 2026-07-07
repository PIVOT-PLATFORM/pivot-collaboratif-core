package fr.pivot.collaboratif.config;

import fr.pivot.collaboratif.whiteboard.ws.StompHandshakeHandler;
import fr.pivot.collaboratif.whiteboard.ws.StompHandshakeInterceptor;
import fr.pivot.collaboratif.whiteboard.ws.WhiteboardChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * Spring WebSocket / STOMP configuration for the collaboratif module.
 *
 * <p>Registers the STOMP endpoint at {@code /ws/whiteboard} (relative to the application
 * context-path {@code /api/collaboratif}). The full WebSocket URL in production is:
 * {@code ws://{host}/api/collaboratif/ws/whiteboard}.
 *
 * <p>Broker configuration uses an in-process {@link org.springframework.messaging.simp.broker.SimpleBroker}
 * for {@code /topic} destinations. For production multi-instance deployments, this must be
 * replaced with a STOMP relay to ActiveMQ (port 61613) to ensure that events published by
 * one server instance are delivered to subscribers on other instances — see the platform
 * architecture documentation and {@code CLAUDE.md} (§Temps réel). The relay config is not
 * yet activated because an ActiveMQ Testcontainer would be required for IT tests, and
 * the {@code SimpleBroker} is sufficient for the isolation guarantees tested in EN08.1.
 *
 * <p>The inbound channel is instrumented with {@link WhiteboardChannelInterceptor} which
 * enforces board membership on every SUBSCRIBE and SEND frame. The HTTP handshake is
 * guarded by {@link StompHandshakeInterceptor} and {@link StompHandshakeHandler} which
 * reject connections missing identity headers with HTTP 401.
 *
 * <p>Payload size is capped at 64 KB per frame as required by EN08.1.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Maximum STOMP frame size accepted from clients (64 KB). */
    private static final int MESSAGE_SIZE_LIMIT = 64 * 1024;

    private final WhiteboardChannelInterceptor whiteboardChannelInterceptor;
    private final String allowedOrigins;

    /**
     * Creates the WebSocket configuration.
     *
     * @param whiteboardChannelInterceptor the STOMP frame interceptor for board authorization
     * @param allowedOrigins               CORS-allowed origins from application configuration
     */
    public WebSocketConfig(
            final WhiteboardChannelInterceptor whiteboardChannelInterceptor,
            @Value("${pivot.cors.allowed-origins:*}") final String allowedOrigins) {
        this.whiteboardChannelInterceptor = whiteboardChannelInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * Configures the in-process simple message broker for topic subscriptions, heartbeat,
     * and the application destination prefix for client-to-server messages.
     *
     * <p>Heartbeat (US08.3.1): server sends every 25 s, server expects client heartbeat
     * within 30 s. A client absent for 30 s is considered disconnected; the Angular client
     * is responsible for reconnection (US08.3.2b exponential back-off).
     *
     * <p>The {@link ThreadPoolTaskScheduler} is required by the {@code SimpleBroker} to
     * drive the heartbeat task; it is created inline to avoid a named-bean clash with
     * other schedulers in the context.
     *
     * @param config the message broker registry to configure
     */
    @Override
    public void configureMessageBroker(final MessageBrokerRegistry config) {
        ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("ws-heartbeat-");
        heartbeatScheduler.initialize();

        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{25000L, 30000L})
                .setTaskScheduler(heartbeatScheduler);
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Registers the STOMP WebSocket endpoint with the identity-enforcing handshake handler
     * and interceptor.
     *
     * @param registry the STOMP endpoint registry
     */
    @Override
    public void registerStompEndpoints(final StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        registry.addEndpoint("/ws/whiteboard")
                .setHandshakeHandler(new StompHandshakeHandler())
                .addInterceptors(new StompHandshakeInterceptor())
                .setAllowedOriginPatterns(origins);
    }

    /**
     * Caps the maximum inbound frame size at 64 KB to prevent oversized payloads.
     *
     * @param registration the transport registration to configure
     */
    @Override
    public void configureWebSocketTransport(final WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT);
    }

    /**
     * Registers the {@link WhiteboardChannelInterceptor} on the client inbound channel so
     * that every SUBSCRIBE and SEND frame is authorised before reaching the broker.
     *
     * @param registration the inbound channel registration
     */
    @Override
    public void configureClientInboundChannel(final ChannelRegistration registration) {
        registration.interceptors(whiteboardChannelInterceptor);
    }
}
