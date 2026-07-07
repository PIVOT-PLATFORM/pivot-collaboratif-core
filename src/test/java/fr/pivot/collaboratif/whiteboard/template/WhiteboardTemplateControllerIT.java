package fr.pivot.collaboratif.whiteboard.template;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WhiteboardTemplateController} (US08.4.1), exercising the
 * full Spring context against a real PostgreSQL database (including the Flyway-seeded
 * templates) and Redis, both provided by Testcontainers.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path
 * directly, without the {@code server.servlet.context-path} prefix — paths used here
 * start with {@code /whiteboard/templates}, not {@code /api/collaboratif/...}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardTemplateControllerIT {

    private static final String BASE_PATH = "/whiteboard/templates";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived datasource and Redis connection properties to the
     * Spring context via dynamic property sources.
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
    }

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID TENANT_A = UUID.randomUUID();

    /** Sets up MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    /**
     * Given the 3 templates seeded via Flyway, when GET /whiteboard/templates is called,
     * then it returns HTTP 200 with all 3 templates, ordered, "Vierge" (blank) absent.
     */
    @Test
    void listTemplates_returnsTheThreeSeededGlobalTemplates() throws Exception {
        mockMvc.perform(get(BASE_PATH)
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("BRAINSTORM"))
                .andExpect(jsonPath("$[1].code").value("RETROSPECTIVE"))
                .andExpect(jsonPath("$[2].code").value("USER_STORY_MAP"))
                .andExpect(jsonPath("$[0].name").value("Brainstorm"))
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(jsonPath("$[0].thumbnailUrl").isString());
    }

    /**
     * Given the X-Pivot-User-Id and X-Pivot-Tenant-Id headers are absent,
     * when GET /whiteboard/templates is called, then it returns HTTP 401 Unauthorized.
     */
    @Test
    void listTemplates_missingPrincipalHeaders_returns401() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isUnauthorized());
    }
}
