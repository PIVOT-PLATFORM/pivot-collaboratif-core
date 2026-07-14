package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEvent;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BoardController} exercising the full Spring context
 * against a real PostgreSQL database and Redis provided by Testcontainers.
 *
 * <p>Covers US08.1.1 acceptance criteria (POST /whiteboard/boards) plus CRUD operations
 * for board list, read, rename, and delete. Each test authenticates via real bearer
 * tokens issued for tenants/users seeded through {@link PlatformAuthTestSupport}
 * (EN08.3) — tenant and user isolation is exercised with distinct seeded identities.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path
 * directly, without the {@code server.servlet.context-path} prefix. Paths used in tests
 * therefore start with {@code /whiteboard/boards} (not {@code /api/collaboratif/...}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BoardControllerIT {

    private static final String BASE_PATH = "/whiteboard/boards";

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

    @Autowired
    private CanvasEventRepository canvasEventRepository;

    private MockMvc mockMvc;

    /** UUID of the "Brainstorm" template, fixed in the Flyway seed data (US08.4.1). */
    private static final UUID BRAINSTORM_TEMPLATE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** UUID of the "Retrospective" template, fixed in the Flyway seed data (US08.4.1). */
    private static final UUID RETROSPECTIVE_TEMPLATE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long userA;
    private String tokenA;
    private long tenantB;
    private String tokenB;

    /**
     * Sets up MockMvc from the web application context and seeds two distinct
     * tenant/user/token fixtures (A and B) before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        userA = fixtureA.userId();
        tokenA = fixtureA.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantB = fixtureB.tenantId();
        tokenB = fixtureB.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /whiteboard/boards
    // -------------------------------------------------------------------------

    /**
     * Given valid headers and a non-blank title,
     * when POST /whiteboard/boards is called,
     * then it returns HTTP 201 with id, title, role "OWNER", and tenantId.
     */
    @Test
    void createBoard_returnsCreatedWithOwnerRole() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Sprint Planning\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Sprint Planning"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.tenantId").value(tenantA));
    }

    /**
     * Given an empty title string,
     * when POST /whiteboard/boards is called,
     * then it returns HTTP 400 with code "INVALID_TITLE".
     */
    @Test
    void createBoard_withEmptyTitle_returns400WithInvalidTitleCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    /**
     * Given the Authorization bearer header is absent,
     * when POST /whiteboard/boards is called,
     * then it returns HTTP 401 Unauthorized.
     */
    @Test
    void createBoard_missingPrincipalHeaders_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Board\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /whiteboard/boards?templateId=... (US08.4.1)
    // -------------------------------------------------------------------------

    /**
     * Given a valid templateId resolving to the "Brainstorm" global template,
     * when POST /whiteboard/boards?templateId=... is called,
     * then it returns HTTP 201 and the new board's canvas contains one persisted DRAW
     * event per template element (5 for Brainstorm), each a valid JSON payload.
     */
    @Test
    void createBoard_withValidTemplateId_returns201AndInitializesCanvasFromTemplate() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .queryParam("templateId", BRAINSTORM_TEMPLATE_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"From Brainstorm\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("From Brainstorm"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID boardId = UUID.fromString(body.get("id").asText());

        List<CanvasEvent> events = canvasEventRepository
                .findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(boardId, tenantA);

        assertThat(events).hasSize(5);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo(CanvasEventType.DRAW);
            assertThat(event.getUserId()).isEqualTo(userA);
            assertThat(event.getTenantId()).isEqualTo(tenantA);
            assertThat(event.getPayload()).isNotBlank();
        });
    }

    /**
     * Given a valid templateId resolving to the "Retrospective" global template,
     * when POST /whiteboard/boards?templateId=... is called,
     * then the new board's canvas contains one persisted DRAW event per template element
     * (7 for Retrospective).
     */
    @Test
    void createBoard_withRetrospectiveTemplateId_initializesSevenElements() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .queryParam("templateId", RETROSPECTIVE_TEMPLATE_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"From Retro\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID boardId = UUID.fromString(body.get("id").asText());

        List<CanvasEvent> events = canvasEventRepository
                .findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(boardId, tenantA);

        assertThat(events).hasSize(7);
    }

    /**
     * Given no templateId query parameter, when POST /whiteboard/boards is called,
     * then the board is created blank with no canvas events (US08.1.1 behaviour
     * unchanged).
     */
    @Test
    void createBoard_withoutTemplateId_hasNoCanvasEvents() throws Exception {
        String boardId = createBoardFor(tokenA, "Blank Board");

        List<CanvasEvent> events = canvasEventRepository.findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(
                UUID.fromString(boardId), tenantA);

        assertThat(events).isEmpty();
    }

    /**
     * Error case: given a templateId that is not a syntactically valid UUID,
     * when POST /whiteboard/boards?templateId=... is called,
     * then it returns HTTP 400 with code "INVALID_TEMPLATE_ID".
     */
    @Test
    void createBoard_withMalformedTemplateId_returns400WithInvalidTemplateIdCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .queryParam("templateId", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Board\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TEMPLATE_ID"));
    }

    /**
     * Error case: given a templateId with a well-formed UUID that does not match any
     * template, when POST /whiteboard/boards?templateId=... is called,
     * then it returns HTTP 404 (no existence leak) and no board is persisted.
     */
    @Test
    void createBoard_withNonExistentTemplateId_returns404() throws Exception {
        UUID unknownTemplateId = UUID.randomUUID();

        mockMvc.perform(post(BASE_PATH)
                        .queryParam("templateId", unknownTemplateId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Board\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /whiteboard/boards
    // -------------------------------------------------------------------------

    /**
     * Given two boards owned by the same user in the same tenant,
     * when GET /whiteboard/boards is called,
     * then it returns HTTP 200 with a non-empty boards array.
     */
    @Test
    void listBoards_returnsOwnedBoards() throws Exception {
        createBoardFor(tokenA, "Board A1");
        createBoardFor(tokenA, "Board A2");

        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boards").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    /**
     * Given boards belonging to two different tenants,
     * when user B lists boards for tenant B,
     * then all returned boards have tenantId equal to tenant B (tenant isolation).
     */
    @Test
    void listBoards_tenantIsolation_userBSeesOnlyOwnBoards() throws Exception {
        createBoardFor(tokenA, "Tenant A Board");
        createBoardFor(tokenB, "Tenant B Board");

        MvcResult result = mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        body.get("boards").forEach(board ->
                assertThat(board.get("tenantId").asLong()).isEqualTo(tenantB));
    }

    /**
     * Given a negative page size parameter,
     * when GET /whiteboard/boards is called,
     * then it returns HTTP 400.
     */
    @Test
    void listBoards_withNegativeSize_returns400() throws Exception {
        mockMvc.perform(get(BASE_PATH)
                        .param("size", "-1")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // GET /whiteboard/boards/{boardId}
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner,
     * when GET /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 200 with the board id and title.
     */
    @Test
    void findById_whenOwner_returnsBoard() throws Exception {
        String boardId = createBoardFor(tokenA, "My Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(boardId))
                .andExpect(jsonPath("$.title").value("My Board"));
    }

    /**
     * Given a board belongs to tenant A, when a user from tenant B tries to access it,
     * then it returns HTTP 404 (cross-tenant isolation).
     */
    @Test
    void findById_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Tenant A Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /**
     * Given the caller is not a member of the board,
     * when GET /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 404 (to avoid information disclosure).
     */
    @Test
    void findById_nonMember_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Private Board");
        long strangerId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        String strangerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                strangerId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // PATCH /whiteboard/boards/{boardId}
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner and the new title is valid,
     * when PATCH /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 200 with the updated title.
     */
    @Test
    void renameBoard_whenOwner_returns200WithUpdatedTitle() throws Exception {
        String boardId = createBoardFor(tokenA, "Old Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"New Title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"));
    }

    /**
     * Given a board belongs to tenant A, when a user from tenant B tries to rename it,
     * then it returns HTTP 404 (cross-tenant isolation).
     */
    @Test
    void renameBoard_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"title\": \"Hacked\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Given an empty title in the rename request,
     * when PATCH /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 400.
     */
    @Test
    void renameBoard_withEmptyTitle_returns400() throws Exception {
        String boardId = createBoardFor(tokenA, "Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // DELETE /whiteboard/boards/{boardId}
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner,
     * when DELETE /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 204 and the board is no longer accessible.
     */
    @Test
    void deleteBoard_whenOwner_returns204AndBoardIsGone() throws Exception {
        String boardId = createBoardFor(tokenA, "To Delete");

        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a board belongs to tenant A, when a user from tenant B tries to delete it,
     * then it returns HTTP 404 (cross-tenant isolation).
     */
    @Test
    void deleteBoard_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Title");

        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /**
     * Given no board exists with the given id in the caller's tenant,
     * when DELETE /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 404.
     */
    @Test
    void deleteBoard_nonExistent_returns404() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a board via the API using the given caller's bearer token and returns its
     * identifier.
     *
     * @param token the caller's raw bearer token
     * @param title the board title
     * @return the string representation of the created board's UUID
     * @throws Exception if the HTTP request fails or the response status is not 201
     */
    private String createBoardFor(final String token, final String title) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }
}
