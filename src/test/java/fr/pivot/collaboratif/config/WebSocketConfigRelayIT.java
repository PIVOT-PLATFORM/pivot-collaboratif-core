package fr.pivot.collaboratif.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for EN07.3 — proves the {@code /topic/collaboratif.} STOMP broker relay
 * registered in {@link WebSocketConfig} actually connects to a real ActiveMQ broker.
 *
 * <p>Uses a real {@code apache/activemq-classic} container (same image family as the shared
 * broker built in {@code pivot-core}) rather than a pub/sub round trip: Spring's
 * {@code StompBrokerRelayMessageHandler} opens a "system" TCP connection to the broker at
 * context startup independent of any registered WebSocket endpoint, and publishes a
 * {@link BrokerAvailabilityEvent} when that connection succeeds — this is the idiomatic,
 * minimal way to verify relay connectivity without needing a full client session.
 *
 * <p>Does not touch the existing whiteboard {@code SimpleBroker} registration at all — the
 * pre-existing whiteboard test suite ({@code WhiteboardWebSocketIT}, {@code WhiteboardPresenceIT},
 * etc.) is left unmodified by this Enabler and covers that registration's own behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WebSocketConfigRelayIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> activemq =
            new GenericContainer<>(DockerImageName.parse("apache/activemq-classic:6.2.0"))
                    .withExposedPorts(61613, 8161);

    /**
     * Supplies Testcontainer-derived connection properties to the Spring context.
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // application-test.yml disables the relay by default (see WebSocketConfig's class
        // JavaDoc) — this is the one test that must actually enable and exercise it.
        registry.add("pivot.activemq.relay-enabled", () -> true);
        registry.add("pivot.activemq.relay-host", activemq::getHost);
        registry.add("pivot.activemq.relay-port", () -> activemq.getMappedPort(61613));
    }

    @Autowired
    private BrokerAvailabilityLatch availabilityLatch;

    /**
     * Given the EN07.3 STOMP broker relay configured against a real ActiveMQ broker,
     * when the Spring context starts,
     * then a {@link BrokerAvailabilityEvent} reporting the broker as available is published
     * within 15 seconds (proves the relay's system connection actually reached the broker).
     *
     * @throws InterruptedException if interrupted while awaiting the event
     */
    @Test
    void relayConnectsToSharedActiveMqBroker() throws InterruptedException {
        boolean becameAvailable = availabilityLatch.awaitAvailable(15, TimeUnit.SECONDS);
        assertThat(becameAvailable)
                .as("BrokerAvailabilityEvent(true) should be published once the relay's "
                        + "system connection reaches the ActiveMQ broker")
                .isTrue();
    }

    /**
     * Test-only listener that captures the first {@code BrokerAvailabilityEvent} reporting
     * the broker as available, exposed via a blocking wait so the test does not need to poll.
     */
    @TestConfiguration
    static class BrokerAvailabilityTestConfig {

        /**
         * Registers the {@link BrokerAvailabilityLatch} bean used by the test.
         *
         * @return a new, unset latch
         */
        @Bean
        BrokerAvailabilityLatch brokerAvailabilityLatch() {
            return new BrokerAvailabilityLatch();
        }
    }

    /**
     * Listens for {@link BrokerAvailabilityEvent} and releases a latch the first time the
     * broker is reported available.
     */
    static class BrokerAvailabilityLatch implements ApplicationListener<BrokerAvailabilityEvent> {

        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onApplicationEvent(final BrokerAvailabilityEvent event) {
            if (event.isBrokerAvailable()) {
                latch.countDown();
            }
        }

        /**
         * Blocks until the broker has been reported available, or the timeout elapses.
         *
         * @param timeout the maximum time to wait
         * @param unit    the time unit of the timeout argument
         * @return {@code true} if the broker became available before the timeout elapsed
         * @throws InterruptedException if interrupted while waiting
         */
        boolean awaitAvailable(final long timeout, final TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
}
