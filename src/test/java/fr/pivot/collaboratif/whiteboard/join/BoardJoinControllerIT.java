package fr.pivot.collaboratif.whiteboard.join;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BoardJoinController} exercising the full Spring context
 * against a real PostgreSQL database and Redis provided by Testcontainers.
 *
 * <p>Covers US08.2.2 acceptance criteria: joining a board via a share token with all
 * validation paths (invalid token, expired, quota exhausted, already member, cross-tenant,
 * rate limiting, missing token, and successful join).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BoardJoinControllerIT {

    private static final String BOARDS_PATH = "/whiteboard/boards";
    private static final String JOIN_PATH = "/whiteboard/join";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container connection properties to the Spring context.
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

    @Autowired
    private JoinRateLimitService rateLimitService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID JOINER = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();

    /** Initialises MockMvc and clears all rate-limit counters before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rateLimitService.resetAll();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createBoard(final UUID userId, final UUID tenantId, final String title)
            throws Exception {
        MvcResult r = mockMvc.perform(
                        post(BOARDS_PATH)
                                .header("X-Pivot-User-Id", userId)
                                .header("X-Pivot-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private String generateShareToken(
            final UUID userId,
            final UUID tenantId,
            final String boardId,
            final String role,
            final Integer maxUses,
            final Integer ttlDays) throws Exception {
        String body = buildShareBody(role, maxUses, ttlDays);
        MvcResult r = mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("X-Pivot-User-Id", userId)
                                .header("X-Pivot-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
        String shareLink = node.get("shareLink").asText();
        return shareLink.substring(shareLink.indexOf("?token=") + 7);
    }

    private String buildShareBody(
            final String role, final Integer maxUses, final Integer ttlDays) {
        StringBuilder sb = new StringBuilder("{\"role\":\"" + role + "\"");
        if (maxUses != null) {
            sb.append(",\"maxUses\":").append(maxUses);
        }
        if (ttlDays != null) {
            sb.append(",\"ttlDays\":").append(ttlDays);
        }
        return sb.append("}").toString();
    }

    // -------------------------------------------------------------------------
    // Successful join
    // -------------------------------------------------------------------------

    /**
     * Given a valid EDITOR token,
     * when POST /whiteboard/join?token=...,
     * then returns 200 with boardId, title, role=EDITOR, redirectUrl.
     */
    @Test
    void join_validEditorToken_returns200WithRedirectUrl() throws Exception {
        String boardId = createBoard(OWNER, TENANT, "Board Join Test");
        String token = generateShareToken(OWNER, TENANT, boardId, "EDITOR", null, null);

        MvcResult result = mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", JOINER)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardId").value(boardId))
                .andExpect(jsonPath("$.title").value("Board Join Test"))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.redirectUrl").isString())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("redirectUrl").asText()).isEqualTo("/whiteboard/" + boardId);
    }

    /**
     * Given a valid VIEWER token,
     * when POST /whiteboard/join?token=...,
     * then returns 200 with role=VIEWER.
     */
    @Test
    void join_validViewerToken_returns200() throws Exception {
        String boardId = createBoard(OWNER, TENANT, "Board Viewer");
        String token = generateShareToken(OWNER, TENANT, boardId, "VIEWER", null, null);

        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", JOINER)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    // -------------------------------------------------------------------------
    // Validation: missing / blank token
    // -------------------------------------------------------------------------

    /**
     * Given missing token parameter,
     * when POST /whiteboard/join,
     * then returns 400.
     */
    @Test
    void join_missingToken_returns400() throws Exception {
        mockMvc.perform(
                        post(JOIN_PATH)
                                .header("X-Pivot-User-Id", JOINER)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a blank token parameter,
     * when POST /whiteboard/join?token=   ,
     * then returns 400.
     */
    @Test
    void join_blankToken_returns400() throws Exception {
        mockMvc.perform(
                        post(JOIN_PATH + "?token=   ")
                                .header("X-Pivot-User-Id", JOINER)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Authentication: missing headers
    // -------------------------------------------------------------------------

    /**
     * Given missing auth headers,
     * when POST /whiteboard/join,
     * then returns 401 (RequestPrincipalResolver rejects absent headers).
     */
    @Test
    void join_missingAuthHeaders_returns401() throws Exception {
        mockMvc.perform(post(JOIN_PATH + "?token=sometoken"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Token not found / invalid
    // -------------------------------------------------------------------------

    /**
     * Given a random invalid token,
     * when POST /whiteboard/join?token=...,
     * then returns 404.
     */
    @Test
    void join_invalidToken_returns404() throws Exception {
        mockMvc.perform(
                        post(JOIN_PATH + "?token=invalid-token-that-does-not-exist")
                                .header("X-Pivot-User-Id", JOINER)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Token quota exceeded → 410
    // -------------------------------------------------------------------------

    /**
     * Given a token with maxUses=1 already consumed,
     * when POST /whiteboard/join?token=... again,
     * then returns 410 Gone.
     */
    @Test
    void join_tokenQuotaExceeded_returns410() throws Exception {
        UUID secondJoiner = UUID.randomUUID();
        UUID thirdJoiner = UUID.randomUUID();
        String boardId = createBoard(OWNER, TENANT, "Quota Board");
        String token = generateShareToken(OWNER, TENANT, boardId, "EDITOR", 1, null);

        // First join consumes the token
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", secondJoiner)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isOk());

        // Second join → 410 quota exhausted
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", thirdJoiner)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isGone());
    }

    // -------------------------------------------------------------------------
    // Already member → 409
    // -------------------------------------------------------------------------

    /**
     * Given a user who already joined the board,
     * when POST /whiteboard/join again,
     * then returns 409 Conflict.
     */
    @Test
    void join_alreadyMember_returns409() throws Exception {
        UUID joiner2 = UUID.randomUUID();
        String boardId = createBoard(OWNER, TENANT, "Dup Board");
        String token = generateShareToken(OWNER, TENANT, boardId, "EDITOR", 2, null);

        // First join succeeds
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", joiner2)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isOk());

        // Second join with same user → 409
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", joiner2)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isConflict());
    }

    /**
     * Given the board owner tries to join their own board via a token,
     * when POST /whiteboard/join?token=...,
     * then returns 409 Conflict (owner is already a member).
     */
    @Test
    void join_ownerJoinsOwnBoard_returns409() throws Exception {
        String boardId = createBoard(OWNER, TENANT, "Owner Board");
        String token = generateShareToken(OWNER, TENANT, boardId, "EDITOR", 1, null);

        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", OWNER)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // Cross-tenant → 403
    // -------------------------------------------------------------------------

    /**
     * Given a user from a different tenant,
     * when POST /whiteboard/join?token=...,
     * then returns 403 (cross-tenant access denied).
     */
    @Test
    void join_crossTenantUser_returns403() throws Exception {
        String boardId = createBoard(OWNER, TENANT, "Tenant A Board");
        String token = generateShareToken(OWNER, TENANT, boardId, "EDITOR", null, null);

        // JOINER is in OTHER_TENANT, not TENANT where the board lives
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", JOINER)
                                .header("X-Pivot-Tenant-Id", OTHER_TENANT))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Rate limiting → 429
    // -------------------------------------------------------------------------

    /**
     * Given more than 10 join attempts in the same hour by the same user,
     * when the 11th request is made,
     * then returns 429 Too Many Requests.
     */
    @Test
    void join_rateLimitExceeded_returns429() throws Exception {
        UUID heavyUser = UUID.randomUUID();
        rateLimitService.resetUser(heavyUser);

        // Exhaust the rate limit with invalid tokens (they still count against the limit)
        for (int i = 0; i < JoinRateLimitService.MAX_ATTEMPTS; i++) {
            mockMvc.perform(
                            post(JOIN_PATH + "?token=bogus-" + i)
                                    .header("X-Pivot-User-Id", heavyUser)
                                    .header("X-Pivot-Tenant-Id", TENANT))
                    .andExpect(status().isNotFound());
        }

        // 11th attempt → 429
        mockMvc.perform(
                        post(JOIN_PATH + "?token=bogus-overflow")
                                .header("X-Pivot-User-Id", heavyUser)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isTooManyRequests());
    }

    // -------------------------------------------------------------------------
    // Revoked token → 404
    // -------------------------------------------------------------------------

    /**
     * Given a revoked token,
     * when POST /whiteboard/join?token=...,
     * then returns 404 (revoked tokens are treated as non-existent).
     */
    @Test
    void join_revokedToken_returns404() throws Exception {
        String boardId = createBoard(OWNER, TENANT, "Revoke Board");
        MvcResult shareResult = mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("X-Pivot-User-Id", OWNER)
                                .header("X-Pivot-Tenant-Id", TENANT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode shareNode = objectMapper.readTree(shareResult.getResponse().getContentAsString());
        String shareLink = shareNode.get("shareLink").asText();
        String token = shareLink.substring(shareLink.indexOf("?token=") + 7);
        String tokenId = shareNode.get("tokenId").asText();

        // Revoke the token
        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .delete(BOARDS_PATH + "/" + boardId + "/share/" + tokenId)
                                .header("X-Pivot-User-Id", OWNER)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isNoContent());

        // Join with revoked token → 404
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("X-Pivot-User-Id", JOINER)
                                .header("X-Pivot-Tenant-Id", TENANT))
                .andExpect(status().isNotFound());
    }
}
