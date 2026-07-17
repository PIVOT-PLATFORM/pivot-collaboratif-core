package fr.pivot.collaboratif.whiteboard.importer;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the US08.13.1 50&nbsp;MB body-size AC on
 * {@code POST /whiteboard/boards/{boardId}/import/klaxoon}: "corps d'import dépassant 50 Mo → 413
 * avant tout traitement".
 *
 * <p>Deliberately not a {@code MockMvc} test: {@code MockMvcBuilders.webAppContextSetup(...)}
 * dispatches straight to the {@code DispatcherServlet}, bypassing the real servlet filter chain —
 * {@link ImportBodySizeLimitFilter} would never run, so the 413 behaviour would go unverified. This
 * class instead drives a real embedded Tomcat instance over a real HTTP client
 * ({@link HttpClient}), exactly like the existing STOMP transport-level tests in this module
 * ({@code WhiteboardOversizedDrawPayloadIT}, {@code WhiteboardRateLimitEnforcementIT}).
 *
 * <p>The filter rejects on {@code Content-Length} alone before reading a single body byte, so no
 * board needs to exist and no valid role is required for this specific assertion — the guard is
 * unconditional and runs ahead of any business logic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardImportBodySizeLimitIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived datasource and Redis connection properties to the Spring
     * context and seeds the {@code public} schema before Flyway runs.
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        PlatformAuthTestSupport.createPublicSchema(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @LocalServerPort
    private int port;

    /**
     * Given a request body over 50 MB, when it is posted to the Klaxoon import endpoint, then the
     * server responds 413 without ever reaching the controller/service layer.
     */
    @Test
    void oversizedImportBody_returns413() throws Exception {
        AuthFixture fixture = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        // 50 * 1024 * 1024 + 1 bytes of raw content — JSON validity is irrelevant here, the
        // filter rejects purely on size (via the Content-Length header), before any parsing.
        byte[] oversizedBody = new byte[(50 * 1024 * 1024) + 1];
        java.util.Arrays.fill(oversizedBody, (byte) 'a');

        String url = "http://localhost:" + port
                + "/api/collaboratif/whiteboard/boards/" + UUID.randomUUID() + "/import/klaxoon";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", fixture.authorizationHeader())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(oversizedBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
    }
}
