package fr.pivot.collaboratif.whiteboard.join;

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

import java.time.Instant;

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
 * rate limiting, missing token, and successful join). Callers authenticate via real bearer
 * tokens issued for tenants/users seeded through {@link PlatformAuthTestSupport} (EN08.3).
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
     * Supplies container connection properties to the Spring context and seeds the
     * {@code public} schema (owned by {@code pivot-core}) before the Spring context and its
     * Flyway run start.
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
    private JoinRateLimitService rateLimitService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantId;
    private String ownerToken;
    private String joinerToken;
    private String otherTenantToken;

    /**
     * Initialises MockMvc, clears all rate-limit counters, and seeds an OWNER/JOINER pair in
     * one tenant plus a lone user in a second tenant (cross-tenant scenarios) before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rateLimitService.resetAll();

        AuthFixture owner = PlatformAuthTestSupport.seedActiveUserWithToken(jdbcUrl(), dbUser(), dbPassword());
        tenantId = owner.tenantId();
        ownerToken = owner.rawToken();

        long joinerId = PlatformAuthTestSupport.seedUser(jdbcUrl(), dbUser(), dbPassword(), tenantId, true);
        joinerToken = tokenFor(joinerId);

        AuthFixture otherTenantUser =
                PlatformAuthTestSupport.seedActiveUserWithToken(jdbcUrl(), dbUser(), dbPassword());
        otherTenantToken = otherTenantUser.rawToken();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    private String dbUser() {
        return postgres.getUsername();
    }

    private String dbPassword() {
        return postgres.getPassword();
    }

    private long newUserInTenant(final long forTenantId) throws Exception {
        return PlatformAuthTestSupport.seedUser(jdbcUrl(), dbUser(), dbPassword(), forTenantId, true);
    }

    private String tokenFor(final long userId) throws Exception {
        return PlatformAuthTestSupport.issueToken(
                jdbcUrl(), dbUser(), dbPassword(), userId, "active", Instant.now().plusSeconds(3600));
    }

    private String createBoard(final String token, final String title) throws Exception {
        MvcResult r = mockMvc.perform(
                        post(BOARDS_PATH)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private String generateShareToken(
            final String token,
            final String boardId,
            final String role,
            final Integer maxUses,
            final Integer ttlDays) throws Exception {
        String body = buildShareBody(role, maxUses, ttlDays);
        MvcResult r = mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + token)
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
        String boardId = createBoard(ownerToken, "Board Join Test");
        String token = generateShareToken(ownerToken, boardId, "EDITOR", null, null);

        MvcResult result = mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + joinerToken))
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
        String boardId = createBoard(ownerToken, "Board Viewer");
        String token = generateShareToken(ownerToken, boardId, "VIEWER", null, null);

        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + joinerToken))
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
                                .header("Authorization", "Bearer " + joinerToken))
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
                                .header("Authorization", "Bearer " + joinerToken))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Authentication: missing headers
    // -------------------------------------------------------------------------

    /**
     * Given no Authorization header,
     * when POST /whiteboard/join,
     * then returns 401 (RequestPrincipalResolver rejects the missing header).
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
                                .header("Authorization", "Bearer " + joinerToken))
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
        String secondJoinerToken = tokenFor(newUserInTenant(tenantId));
        String thirdJoinerToken = tokenFor(newUserInTenant(tenantId));
        String boardId = createBoard(ownerToken, "Quota Board");
        String token = generateShareToken(ownerToken, boardId, "EDITOR", 1, null);

        // First join consumes the token
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + secondJoinerToken))
                .andExpect(status().isOk());

        // Second join → 410 quota exhausted
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + thirdJoinerToken))
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
        String joiner2Token = tokenFor(newUserInTenant(tenantId));
        String boardId = createBoard(ownerToken, "Dup Board");
        String token = generateShareToken(ownerToken, boardId, "EDITOR", 2, null);

        // First join succeeds
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + joiner2Token))
                .andExpect(status().isOk());

        // Second join with same user → 409
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + joiner2Token))
                .andExpect(status().isConflict());
    }

    /**
     * Given the board owner tries to join their own board via a token,
     * when POST /whiteboard/join?token=...,
     * then returns 409 Conflict (owner is already a member).
     */
    @Test
    void join_ownerJoinsOwnBoard_returns409() throws Exception {
        String boardId = createBoard(ownerToken, "Owner Board");
        String token = generateShareToken(ownerToken, boardId, "EDITOR", 1, null);

        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + ownerToken))
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
        String boardId = createBoard(ownerToken, "Tenant A Board");
        String token = generateShareToken(ownerToken, boardId, "EDITOR", null, null);

        // otherTenantToken resolves to a user in a genuinely different tenant than the board's
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + otherTenantToken))
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
        long heavyUserId = newUserInTenant(tenantId);
        String heavyUserToken = tokenFor(heavyUserId);
        rateLimitService.resetUser(heavyUserId);

        // Exhaust the rate limit with invalid tokens (they still count against the limit)
        for (int i = 0; i < JoinRateLimitService.MAX_ATTEMPTS; i++) {
            mockMvc.perform(
                            post(JOIN_PATH + "?token=bogus-" + i)
                                    .header("Authorization", "Bearer " + heavyUserToken))
                    .andExpect(status().isNotFound());
        }

        // 11th attempt → 429
        mockMvc.perform(
                        post(JOIN_PATH + "?token=bogus-overflow")
                                .header("Authorization", "Bearer " + heavyUserToken))
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
        String boardId = createBoard(ownerToken, "Revoke Board");
        MvcResult shareResult = mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + ownerToken)
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
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        // Join with revoked token → 404
        mockMvc.perform(
                        post(JOIN_PATH + "?token=" + token)
                                .header("Authorization", "Bearer " + joinerToken))
                .andExpect(status().isNotFound());
    }
}
