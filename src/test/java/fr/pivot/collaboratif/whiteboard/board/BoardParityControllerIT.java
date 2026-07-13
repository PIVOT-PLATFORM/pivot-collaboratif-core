package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the whiteboard "visible parity" endpoints on {@link BoardController}
 * against a real PostgreSQL + Redis via Testcontainers (US08.1.6 favorites, US08.1.7
 * trash/soft-delete/restore/permanent, US08.1.8 search, US08.2.4 settings/reset/save-as-template).
 *
 * <p>Each authenticating identity uses a real bearer token seeded through
 * {@link PlatformAuthTestSupport} (EN08.3). Tests exercise happy paths plus 401/403/404/409
 * error cases and cross-tenant isolation. MockMvc dispatches against the servlet path directly
 * (no {@code /api/collaboratif} context prefix), matching {@code BoardControllerIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BoardParityControllerIT {

    private static final String BASE_PATH = "/whiteboard/boards";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived datasource/Redis properties and seeds the {@code public}
     * schema before the Spring context and Flyway start.
     *
     * @param registry the dynamic property registry
     * @throws Exception if seeding the public schema fails
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

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long userA;
    private String tokenA;
    private String tokenB;

    /** Sets up MockMvc and two tenant/user/token fixtures (A and B) before each test. */
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
        tokenB = fixtureB.rawToken();
    }

    // =========================================================================
    // US08.1.6 — Favorites
    // =========================================================================

    @Test
    void ac08_1_6_01_putFavorite_thenReflectedInGetOne() throws Exception {
        String boardId = createBoardFor(tokenA, "Fav Board");

        mockMvc.perform(put(BASE_PATH + "/" + boardId + "/favorite")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(true));
    }

    @Test
    void ac08_1_6_02_putFavoriteTwice_isIdempotent() throws Exception {
        String boardId = createBoardFor(tokenA, "Fav Board");

        mockMvc.perform(put(BASE_PATH + "/" + boardId + "/favorite")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(BASE_PATH + "/" + boardId + "/favorite")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(BASE_PATH + "/" + boardId + "/favorite")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.favorite").value(false));
    }

    @Test
    void ac08_1_6_05_favoriteIsPerUser_editorSeesOwnStateNotOwners() throws Exception {
        String boardId = createBoardFor(tokenA, "Shared");
        AuthFixture editor = seedMember(boardId, BoardRole.EDITOR);

        mockMvc.perform(put(BASE_PATH + "/" + boardId + "/favorite")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // Owner favorited; editor's own state stays independent (false).
        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + editor.rawToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(false));
    }

    @Test
    void ac08_1_6_08_putFavorite_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Tenant A Board");

        mockMvc.perform(put(BASE_PATH + "/" + boardId + "/favorite")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void ac08_1_6_10_putFavorite_noToken_returns401() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");

        mockMvc.perform(put(BASE_PATH + "/" + boardId + "/favorite"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // US08.1.7 — Trash / soft-delete / restore / permanent
    // =========================================================================

    @Test
    void ac08_1_7_01_delete_softDeletes_boardLeavesNormalListButStaysInTrash() throws Exception {
        String boardId = createBoardFor(tokenA, "To Trash");

        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // Not in normal list.
        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        // Present in trash list with deletedAt populated.
        MvcResult trash = mockMvc.perform(get(BASE_PATH + "?trashed=true")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(trash.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode b : body.get("boards")) {
            if (b.get("id").asString().equals(boardId)) {
                found = true;
                org.assertj.core.api.Assertions.assertThat(b.get("deletedAt").isNull()).isFalse();
            }
        }
        org.assertj.core.api.Assertions.assertThat(found).isTrue();
    }

    @Test
    void ac08_1_7_03_restore_bringsBoardBackToNormalList() throws Exception {
        String boardId = createBoardFor(tokenA, "Restore Me");
        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/restore")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    @Test
    void ac08_1_7_07_restore_whenNotInTrash_returns409() throws Exception {
        String boardId = createBoardFor(tokenA, "Not Trashed");

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/restore")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict());
    }

    @Test
    void ac08_1_7_08_restore_whenEditorNotOwner_returns403() throws Exception {
        String boardId = createBoardFor(tokenA, "Shared");
        AuthFixture editor = seedMember(boardId, BoardRole.EDITOR);
        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/restore")
                        .header("Authorization", "Bearer " + editor.rawToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void ac08_1_7_08_delete_whenEditorNotOwner_returns403() throws Exception {
        String boardId = createBoardFor(tokenA, "Shared");
        AuthFixture editor = seedMember(boardId, BoardRole.EDITOR);

        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + editor.rawToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void ac08_1_7_09_restore_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Tenant A");
        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/restore")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void permanentDelete_whenTrashed_returns204ThenBoardGone() throws Exception {
        String boardId = createBoardFor(tokenA, "Purge Me");
        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(BASE_PATH + "/" + boardId + "/permanent")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // Gone from trash too.
        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/restore")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void permanentDelete_whenNotInTrash_returns409() throws Exception {
        String boardId = createBoardFor(tokenA, "Not Trashed");

        mockMvc.perform(delete(BASE_PATH + "/" + boardId + "/permanent")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // US08.1.8 — Search
    // =========================================================================

    @Test
    void ac08_1_8_01_search_filtersByTitleCaseAndAccentInsensitive() throws Exception {
        createBoardFor(tokenA, "Rétrospective Équipe");
        createBoardFor(tokenA, "Sprint Planning");

        MvcResult result = mockMvc.perform(get(BASE_PATH + "?q=retrospective")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(body.get("boards")).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(
                body.get("boards").get(0).get("title").asString()).contains("Rétrospective");
    }

    @Test
    void ac08_1_8_03_search_noMatch_returnsEmptyPage() throws Exception {
        createBoardFor(tokenA, "Alpha");

        mockMvc.perform(get(BASE_PATH + "?q=zzznomatch")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void ac08_1_8_02_search_emptyQuery_returnsAll() throws Exception {
        createBoardFor(tokenA, "One");
        createBoardFor(tokenA, "Two");

        mockMvc.perform(get(BASE_PATH + "?q=")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // =========================================================================
    // US08.2.4 — Settings / reset / save-as-template
    // =========================================================================

    @Test
    void ac08_2_4_02_patch_updatesDescriptionAndActivities_asOwner() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"description\":\"Hello\",\"enabledActivities\":[\"VOTE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Hello"))
                .andExpect(jsonPath("$.enabledActivities[0]").value("VOTE"));
    }

    @Test
    void ac08_2_4_03_patch_unknownActivity_returns400() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"enabledActivities\":[\"NOPE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACTIVITY"));
    }

    @Test
    void ac08_2_4_08_patchDescription_asEditor_returns403() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");
        AuthFixture editor = seedMember(boardId, BoardRole.EDITOR);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + editor.rawToken())
                        .content("{\"description\":\"hack\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ac08_2_4_04_saveAsTemplate_asOwner_returns201() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/save-as-template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\":\"My Template\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Template"))
                .andExpect(jsonPath("$.id").isString());
    }

    @Test
    void ac08_2_4_08_saveAsTemplate_asEditor_returns403() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");
        AuthFixture editor = seedMember(boardId, BoardRole.EDITOR);

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/save-as-template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + editor.rawToken())
                        .content("{\"name\":\"T\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ac08_2_4_06_reset_asOwner_returns204() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/reset")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    void ac08_2_4_07_reset_asEditor_returns204() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");
        AuthFixture editor = seedMember(boardId, BoardRole.EDITOR);

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/reset")
                        .header("Authorization", "Bearer " + editor.rawToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void ac08_2_4_08_reset_asViewer_returns403() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");
        AuthFixture viewer = seedMember(boardId, BoardRole.VIEWER);

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/reset")
                        .header("Authorization", "Bearer " + viewer.rawToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void ac08_2_4_08_reset_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");

        mockMvc.perform(post(BASE_PATH + "/" + boardId + "/reset")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a board via the API and returns its id.
     *
     * @param token the caller's raw bearer token
     * @param title the board title
     * @return the created board's UUID string
     * @throws Exception if the request fails or status is not 201
     */
    private String createBoardFor(final String token, final String title) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asString();
    }

    /**
     * Seeds a new active user in tenant A, adds them to the given board with the given role
     * (directly in {@code collaboratif.board_member}), and returns their auth fixture.
     *
     * @param boardId the board to add the member to
     * @param role    the role to grant
     * @return the new member's auth fixture (token usable as a bearer)
     * @throws Exception if seeding fails
     */
    private AuthFixture seedMember(final String boardId, final BoardRole role) throws Exception {
        long memberUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        String token = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberUserId, "active", Instant.now().plusSeconds(3600));
        insertBoardMember(UUID.fromString(boardId), memberUserId, role);
        return new AuthFixture(tenantA, memberUserId, token);
    }

    /**
     * Inserts a board_member row directly, bypassing the join/share flow (not under test here).
     *
     * @param boardId the board UUID
     * @param userId  the member's user id
     * @param role    the member's role
     * @throws Exception if the insert fails
     */
    private void insertBoardMember(final UUID boardId, final long userId, final BoardRole role)
            throws Exception {
        String sql = "INSERT INTO collaboratif.board_member (board_id, user_id, role, joined_at) "
                + "VALUES (?, ?, ?, now())";
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, boardId);
            ps.setLong(2, userId);
            ps.setString(3, role.name());
            ps.executeUpdate();
        }
    }
}
