package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.board.WhiteboardModuleCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
     * Spring context via dynamic property sources, and seeds the {@code public} schema
     * (owned by {@code pivot-core}) before the Spring context and its Flyway run start.
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

    @Autowired
    private WebApplicationContext wac;

    @MockitoBean
    private WhiteboardModuleCheck moduleCheck;

    private MockMvc mockMvc;

    private long tenantA;
    private String tokenA;

    /**
     * Sets up MockMvc from the web application context before each test, seeds a real
     * tenant/user/token fixture (A) via {@link PlatformAuthTestSupport} (EN08.3), and stubs
     * the module-activation check to "enabled" by default — individual tests override this
     * to exercise the disabled path.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        when(moduleCheck.isEnabled(any())).thenReturn(true);

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        tokenA = fixtureA.rawToken();
    }

    /**
     * Given the 3 templates seeded via Flyway, when GET /whiteboard/templates is called,
     * then it returns HTTP 200 with all 3 templates, ordered, "Vierge" (blank) absent.
     */
    @Test
    void listTemplates_returnsTheThreeSeededGlobalTemplates() throws Exception {
        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
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
     * Given the Authorization bearer header is absent,
     * when GET /whiteboard/templates is called, then it returns HTTP 401 Unauthorized.
     */
    @Test
    void listTemplates_missingPrincipalHeaders_returns401() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Given the whiteboard module is disabled for the caller's tenant, when
     * GET /whiteboard/templates is called, then it returns HTTP 403 Forbidden —
     * a disabled module must reject the request before any template data is returned.
     */
    @Test
    void listTemplates_moduleDisabledForTenant_returns403() throws Exception {
        when(moduleCheck.isEnabled(tenantA)).thenReturn(false);

        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }
}
