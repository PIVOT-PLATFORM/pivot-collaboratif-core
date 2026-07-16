package fr.pivot.collaboratif.whiteboard.me;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /whiteboard/me} (US08.12.2 enabler).
 *
 * <p>Verifies the endpoint returns the authenticated caller's own {@code public.users.id},
 * resolved from the bearer token, and rejects unauthenticated calls — the identity the dot-vote
 * UI relies on to attribute votes to the current user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardMeControllerIT {

    private static final String ME_PATH = "/whiteboard/me";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private AuthFixture user;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        user = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void returns_the_authenticated_caller_own_user_id() throws Exception {
        mockMvc.perform(get(ME_PATH).header("Authorization", "Bearer " + user.rawToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(String.valueOf(user.userId())));
    }

    @Test
    void rejects_an_unauthenticated_call_with_401() throws Exception {
        mockMvc.perform(get(ME_PATH))
                .andExpect(status().isUnauthorized());
    }
}
