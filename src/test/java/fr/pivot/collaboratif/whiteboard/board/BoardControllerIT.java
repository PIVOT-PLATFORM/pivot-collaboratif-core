package fr.pivot.collaboratif.whiteboard.board;

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
 * for board list, read, rename, and delete. Each test uses randomly generated UUIDs
 * for tenant and user isolation.
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Sets up MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    // -------------------------------------------------------------------------
    // POST /whiteboard/boards
    // -------------------------------------------------------------------------

    /**
     * Given valid headers and a non-blank title,
     * when POST /whiteboard/boards is called,
     * then it returns HTTP 201 with id, title, role "owner", and tenantId.
     */
    @Test
    void createBoard_returnsCreatedWithOwnerRole() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A)
                        .content("{\"title\": \"Sprint Planning\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Sprint Planning"))
                .andExpect(jsonPath("$.role").value("owner"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()));
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
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A)
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    /**
     * Given the X-Pivot-User-Id and X-Pivot-Tenant-Id headers are absent,
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
    // GET /whiteboard/boards
    // -------------------------------------------------------------------------

    /**
     * Given two boards owned by the same user in the same tenant,
     * when GET /whiteboard/boards is called,
     * then it returns HTTP 200 with a non-empty boards array.
     */
    @Test
    void listBoards_returnsOwnedBoards() throws Exception {
        createBoardFor(USER_A, TENANT_A, "Board A1");
        createBoardFor(USER_A, TENANT_A, "Board A2");

        mockMvc.perform(get(BASE_PATH)
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A))
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
        createBoardFor(USER_A, TENANT_A, "Tenant A Board");
        createBoardFor(USER_B, TENANT_B, "Tenant B Board");

        MvcResult result = mockMvc.perform(get(BASE_PATH)
                        .header("X-Pivot-User-Id", USER_B)
                        .header("X-Pivot-Tenant-Id", TENANT_B))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        body.get("boards").forEach(board ->
                assertThat(board.get("tenantId").asText()).isEqualTo(TENANT_B.toString()));
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
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A))
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
        String boardId = createBoardFor(USER_A, TENANT_A, "My Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A))
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
        String boardId = createBoardFor(USER_A, TENANT_A, "Tenant A Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("X-Pivot-User-Id", USER_B)
                        .header("X-Pivot-Tenant-Id", TENANT_B))
                .andExpect(status().isNotFound());
    }

    /**
     * Given the caller is not a member of the board,
     * when GET /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 404 (to avoid information disclosure).
     */
    @Test
    void findById_nonMember_returns404() throws Exception {
        String boardId = createBoardFor(USER_A, TENANT_A, "Private Board");
        UUID stranger = UUID.randomUUID();

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("X-Pivot-User-Id", stranger)
                        .header("X-Pivot-Tenant-Id", TENANT_A))
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
        String boardId = createBoardFor(USER_A, TENANT_A, "Old Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A)
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
        String boardId = createBoardFor(USER_A, TENANT_A, "Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pivot-User-Id", USER_B)
                        .header("X-Pivot-Tenant-Id", TENANT_B)
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
        String boardId = createBoardFor(USER_A, TENANT_A, "Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A)
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
        String boardId = createBoardFor(USER_A, TENANT_A, "To Delete");

        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a board belongs to tenant A, when a user from tenant B tries to delete it,
     * then it returns HTTP 404 (cross-tenant isolation).
     */
    @Test
    void deleteBoard_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(USER_A, TENANT_A, "Title");

        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("X-Pivot-User-Id", USER_B)
                        .header("X-Pivot-Tenant-Id", TENANT_B))
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
                        .header("X-Pivot-User-Id", USER_A)
                        .header("X-Pivot-Tenant-Id", TENANT_A))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a board via the API and returns its identifier.
     *
     * @param userId   the user creating the board
     * @param tenantId the tenant the board belongs to
     * @param title    the board title
     * @return the string representation of the created board's UUID
     * @throws Exception if the HTTP request fails or the response status is not 201
     */
    private String createBoardFor(
            final UUID userId, final UUID tenantId, final String title) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pivot-User-Id", userId)
                        .header("X-Pivot-Tenant-Id", tenantId)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }
}
