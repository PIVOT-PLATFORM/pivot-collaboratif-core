package fr.pivot.collaboratif.config;

import fr.pivot.collaboratif.whiteboard.ws.SessionTrackingHandlerDecoratorFactory;
import fr.pivot.collaboratif.whiteboard.ws.StompHandshakeHandler;
import fr.pivot.collaboratif.whiteboard.ws.StompHandshakeInterceptor;
import fr.pivot.collaboratif.whiteboard.ws.WhiteboardChannelInterceptor;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Spring WebSocket / STOMP configuration for the collaboratif module.
 *
 * <p>Registers the STOMP endpoint at {@code /ws/whiteboard} (relative to the application
 * context-path {@code /api/collaboratif}). The full WebSocket URL in production is:
 * {@code ws://{host}/api/collaboratif/ws/whiteboard}.
 *
 * <p>Broker configuration uses an in-process {@link org.springframework.messaging.simp.broker.SimpleBroker}
 * for the whiteboard's own {@code /topic/whiteboard/*} and {@code /queue} traffic (per-board
 * room pub/sub, presence, error notifications) — unchanged by EN07.3, since a browser-facing
 * room-broadcast round trip does not need cross-instance durability guarantees, and this
 * traffic's tested behavior (heartbeat, room isolation, rate limiting — EN08.1/US08.3.1)
 * must not regress. (The prefix was narrowed here from the previous bare {@code "/topic"} to
 * {@code "/topic/whiteboard"} — verified against every {@code /topic/...} destination in this
 * codebase, all of which are already {@code /topic/whiteboard/...}; this only makes the
 * existing scope explicit ahead of the new registration below, no destination changes.)
 *
 * <p><strong>EN07.3</strong> adds a second, independent broker registration — a STOMP relay to
 * the shared ActiveMQ broker (KahaDB persistence, built in {@code pivot-core}) — scoped
 * strictly to the {@code /topic/collaboratif.} prefix (note the trailing dot: destinations use
 * dot-hierarchy, not slashes, because ActiveMQ's wildcard destination matching — used by the
 * broker's per-domain Dead Letter Queue policy, {@code DLQ.collaboratif} — only matches
 * dot-separated segments). This is the cross-module-core domain event bus (future quiz/session/
 * forms features under E30 that need to notify other domains); nothing publishes on it yet.
 * Spring supports both a {@code SimpleBroker} and a {@code StompBrokerRelay} registered
 * simultaneously in the same {@code configureMessageBroker}, each handling its own disjoint
 * destination prefixes as separate message handlers (confirmed against
 * {@code MessageBrokerRegistry.getSimpleBroker()}/{@code getStompBrokerRelay()} — each returns
 * a handler only if its own registration method was called). In production this is fully
 * additive and safe.
 *
 * <p><strong>{@code pivot.activemq.relay-enabled} toggle:</strong> registering the relay is
 * gated behind this flag (default {@code true}). This is not optional polish — empirically,
 * registering a {@code StompBrokerRelay} whose target is unreachable (as happens in every
 * pre-existing whiteboard IT test, which know nothing about EN07.3 and provide no broker)
 * does not fail in isolation: the relay's repeated connection failures were observed to
 * cascade into {@code ConnectionLostException}/"Connection closed" errors on the *whiteboard's
 * own* {@code SimpleBroker} WebSocket sessions in {@code WhiteboardWebSocketIT},
 * {@code WhiteboardCanvasIT} and {@code WhiteboardPresenceIT} — i.e. the two registrations are
 * not as isolated at runtime as the registry API suggests once one of them is actually failing
 * to connect. {@code application-test.yml} sets this flag to {@code false}, so none of the
 * pre-existing tests register the relay at all (byte-for-byte the pre-EN07.3 behavior); the
 * dedicated {@code WebSocketConfigRelayIT} for this Enabler overrides it back to {@code true}
 * against a real Testcontainers broker.
 *
 * <p><strong>Known limitation, not further chased here:</strong> this means the relay is
 * never actually exercised end-to-end against a *live* broker alongside the whiteboard's own
 * WebSocket traffic in this test suite (only in isolation, in {@code WebSocketConfigRelayIT}) —
 * acceptable for this Enabler's scope (wiring the relay, not yet publishing anything on it),
 * flagged here for whoever implements the first feature that actually uses
 * {@code /topic/collaboratif.*}.
 *
 * <p><strong>Known gap</strong> (documented, accepted, consistent with this codebase's existing
 * practice of flagging rather than silently ignoring known limitations — see e.g. pivot-core's
 * unauthenticated-Redis gap note): the existing whiteboard destinations
 * ({@code /topic/whiteboard/{boardId}}, slash-separated) are NOT relayed and therefore never
 * get the broker's named {@code DLQ.collaboratif} routing even if a future US relays them —
 * ActiveMQ's dot-hierarchy wildcard (its {@code destinationPolicy} in {@code activemq.xml})
 * cannot match a slash-delimited destination, which becomes one opaque segment to it (verified
 * empirically). Renaming the whiteboard destinations to dot-hierarchy would fix this but is a
 * breaking change requiring coordination with {@code pivot-collaboratif-ui} — out of scope here.
 *
 * <p>The inbound channel is instrumented with {@link WhiteboardChannelInterceptor} which
 * enforces board membership on every SUBSCRIBE and SEND frame. The HTTP handshake is
 * guarded by {@link StompHandshakeInterceptor} and {@link StompHandshakeHandler}, which
 * validate the {@code Authorization: Bearer} handshake header via the injected {@link
 * AuthenticatedPrincipalResolver} bean (EN08.3, ADR-022) and reject connections with a missing,
 * malformed, or rejected bearer token with HTTP 401 — see {@link StompHandshakeInterceptor}'s
 * JavaDoc for the handshake token convention (a judgment call, not a confirmed contract with
 * {@code pivot-collaboratif-ui}).
 *
 * <p>Payload size is capped at 64 KB per frame as required by EN08.1 — enforced at
 * <strong>two</strong> levels, both required for the US08.3.1 AC ("payload &gt; limite → STOMP
 * ERROR frame sans déconnecter les autres participants") to actually hold:
 * <ol>
 *   <li>{@link WebSocketTransportRegistration#setMessageSizeLimit} — Spring's own STOMP frame
 *       decoder limit. When exceeded, {@code SubProtocolWebSocketHandler} catches the resulting
 *       {@code StompConversionException} and sends a graceful STOMP ERROR frame back to the
 *       sender without touching the connection or any other session — this is the behavior the
 *       AC actually wants.</li>
 *   <li>{@link ServletServerContainerFactoryBean} ({@link #webSocketContainer}) — the underlying
 *       Jakarta WebSocket container's own {@code maxTextMessageBufferSize}. This defaults to
 *       Tomcat's built-in 8 KB, which is <em>smaller</em> than the 64 KB STOMP-level limit above.
 *       Verified empirically (integration test sending a 70 KB DRAW payload): without raising
 *       this container-level buffer, the raw WebSocket frame never reaches Spring's STOMP
 *       decoder at all — Tomcat itself hard-closes the connection with code 1009 ("message too
 *       big") before Spring gets a chance to send the graceful ERROR frame, which is exactly the
 *       disconnect the AC says must NOT happen. Both limits must therefore be configured
 *       together, with the container limit set comfortably above the STOMP-level one, so the
 *       STOMP decoder is always the one that fires first for a payload in the documented range.</li>
 * </ol>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Maximum STOMP frame size accepted from clients (64 KB) — see class JavaDoc. */
    private static final int MESSAGE_SIZE_LIMIT = 64 * 1024;

    /**
     * Underlying Jakarta WebSocket container buffer size (128 KB) — deliberately larger than
     * {@link #MESSAGE_SIZE_LIMIT} so the container never hard-closes a connection before
     * Spring's own STOMP-level limit gets a chance to handle it gracefully. See class JavaDoc.
     */
    private static final int CONTAINER_BUFFER_SIZE = MESSAGE_SIZE_LIMIT * 2;

    /**
     * Destination prefix relayed to the shared ActiveMQ broker for the collaboratif domain.
     *
     * <p>Trailing dot is deliberate: it prevents a hypothetical future domain prefix (e.g.
     * a domain literally named {@code collaboratifx}) from ever accidentally string-matching
     * this one. Spring's {@code AbstractBrokerMessageHandler} only relays destinations that
     * start with a registered prefix — this is the actual, enforced isolation boundary for
     * this module's cross-domain bus traffic (broker-side ACL is a documented, accepted
     * follow-up gap, not implemented here — same posture taken in pivot-pilotage-core and
     * pivot-agilite-core's equivalent EN07.3 changes).
     */
    static final String DOMAIN_RELAY_PREFIX = "/topic/collaboratif.";

    private final WhiteboardChannelInterceptor whiteboardChannelInterceptor;
    private final SessionTrackingHandlerDecoratorFactory sessionTrackingHandlerDecoratorFactory;
    private final AuthenticatedPrincipalResolver authenticatedPrincipalResolver;
    private final String allowedOrigins;
    private final boolean activemqRelayEnabled;
    private final String activemqRelayHost;
    private final int activemqRelayPort;

    /**
     * Creates the WebSocket configuration.
     *
     * @param whiteboardChannelInterceptor           the STOMP frame interceptor for board
     *                                                authorization
     * @param sessionTrackingHandlerDecoratorFactory decorator factory that feeds
     *                                                {@code WhiteboardSessionRegistry}, used by
     *                                                {@code whiteboardChannelInterceptor} to
     *                                                force-close a session after repeated
     *                                                rate-limit violations
     * @param authenticatedPrincipalResolver          the bean that validates a raw bearer token
     *                                                at handshake time (EN08.3, ADR-022), passed
     *                                                to {@link StompHandshakeInterceptor}
     * @param allowedOrigins                          CORS-allowed origins from application
     *                                                configuration
     * @param activemqRelayEnabled                    whether to register the EN07.3 broker relay
     *                                                at all — {@code false} in the {@code test}
     *                                                profile, see class JavaDoc
     * @param activemqRelayHost                       hostname of the shared ActiveMQ broker
     *                                                (EN07.3)
     * @param activemqRelayPort                       STOMP port of the shared ActiveMQ broker
     *                                                (EN07.3)
     */
    public WebSocketConfig(
            final WhiteboardChannelInterceptor whiteboardChannelInterceptor,
            final SessionTrackingHandlerDecoratorFactory sessionTrackingHandlerDecoratorFactory,
            final AuthenticatedPrincipalResolver authenticatedPrincipalResolver,
            @Value("${pivot.cors.allowed-origins:*}") final String allowedOrigins,
            @Value("${pivot.activemq.relay-enabled:true}") final boolean activemqRelayEnabled,
            @Value("${pivot.activemq.relay-host}") final String activemqRelayHost,
            @Value("${pivot.activemq.relay-port}") final int activemqRelayPort) {
        this.whiteboardChannelInterceptor = whiteboardChannelInterceptor;
        this.sessionTrackingHandlerDecoratorFactory = sessionTrackingHandlerDecoratorFactory;
        this.authenticatedPrincipalResolver = authenticatedPrincipalResolver;
        this.allowedOrigins = allowedOrigins;
        this.activemqRelayEnabled = activemqRelayEnabled;
        this.activemqRelayHost = activemqRelayHost;
        this.activemqRelayPort = activemqRelayPort;
    }

    /**
     * Configures the in-process simple message broker for whiteboard topic subscriptions,
     * heartbeat, and the application destination prefix for client-to-server messages —
     * then, additively, the EN07.3 STOMP relay to the shared ActiveMQ broker for the
     * collaboratif-domain cross-module bus.
     *
     * <p>Heartbeat (US08.3.1): server sends every 25 s, server expects client heartbeat
     * within 30 s. A client absent for 30 s is considered disconnected; the Angular client
     * is responsible for reconnection (US08.3.2b exponential back-off). Unaffected by the
     * EN07.3 relay addition below — heartbeat is configured per-registration, and the
     * whiteboard's {@code SimpleBroker} registration is untouched.
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

        config.enableSimpleBroker("/topic/whiteboard", "/queue")
                .setHeartbeatValue(new long[]{25000L, 30000L})
                .setTaskScheduler(heartbeatScheduler);

        // EN07.3 — gated relay registration, see class JavaDoc ("relay-enabled toggle").
        if (activemqRelayEnabled) {
            config.enableStompBrokerRelay(DOMAIN_RELAY_PREFIX)
                    .setRelayHost(activemqRelayHost)
                    .setRelayPort(activemqRelayPort)
                    .setSystemHeartbeatSendInterval(10000)
                    .setSystemHeartbeatReceiveInterval(10000);
        }

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
                .addInterceptors(new StompHandshakeInterceptor(authenticatedPrincipalResolver))
                .setAllowedOriginPatterns(origins);
    }

    /**
     * Caps the maximum inbound frame size at 64 KB to prevent oversized payloads, and registers
     * {@link SessionTrackingHandlerDecoratorFactory} so sessions can later be force-closed by ID
     * (rate-limit strike enforcement, see {@link WhiteboardChannelInterceptor}).
     *
     * @param registration the transport registration to configure
     */
    @Override
    public void configureWebSocketTransport(final WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT);
        registration.addDecoratorFactory(sessionTrackingHandlerDecoratorFactory);
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

    /**
     * Raises the embedded servlet container's own WebSocket text/binary message buffer size to
     * {@value #CONTAINER_BUFFER_SIZE} bytes — see the class JavaDoc's "Payload size" section for
     * why this is required in addition to {@link #configureWebSocketTransport}'s STOMP-level
     * limit, not instead of it.
     *
     * <p>Uses {@link LenientServletServerContainerFactoryBean} rather than the plain
     * {@link ServletServerContainerFactoryBean} directly: the latter's {@code afterPropertiesSet}
     * unconditionally requires a real {@code jakarta.websocket.server.ServerContainer} attribute
     * on the {@code ServletContext}, which only a genuinely running embedded container (Tomcat's
     * {@code WsSci}) ever sets. {@code @SpringBootTest} with the default
     * {@code WebEnvironment.MOCK} (e.g. {@code PivotCollaboratifApplicationTests}) uses a
     * {@code MockServletContext} instead and never sets it — a real, previously-undetected
     * failure surfaced only once a test exercising this bean's happy path (the oversized-payload
     * IT) forced this bean to actually be added.
     *
     * @return the configured container factory bean
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        LenientServletServerContainerFactoryBean container = new LenientServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(CONTAINER_BUFFER_SIZE);
        container.setMaxBinaryMessageBufferSize(CONTAINER_BUFFER_SIZE);
        return container;
    }

    /**
     * A {@link ServletServerContainerFactoryBean} that silently skips configuration instead of
     * failing when no real {@code jakarta.websocket.server.ServerContainer} is available on the
     * {@code ServletContext} (e.g. under a mock servlet environment in tests) — see
     * {@link #webSocketContainer()} for why this is needed.
     */
    static class LenientServletServerContainerFactoryBean extends ServletServerContainerFactoryBean {

        private ServletContext servletContext;

        @Override
        public void setServletContext(final ServletContext servletContext) {
            this.servletContext = servletContext;
            super.setServletContext(servletContext);
        }

        /**
         * Applies the configured buffer sizes only if the servlet context actually exposes a
         * real {@code ServerContainer} attribute; otherwise this is a no-op, leaving
         * {@link #getObject()} to return {@code null} (harmless: nothing in this application
         * depends on this bean's value, only on its side effect of raising the buffer sizes).
         */
        @Override
        public void afterPropertiesSet() {
            if (servletContext != null
                    && servletContext.getAttribute(ServerContainer.class.getName()) != null) {
                super.afterPropertiesSet();
            }
        }
    }
}
