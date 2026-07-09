package fr.pivot.collaboratif.whiteboard.share;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BoardShareController} exercising the full Spring context
 * against a real PostgreSQL database and Redis provided by Testcontainers.
 *
 * <p>Covers US08.2.1 acceptance criteria: generating share tokens (POST) and
 * revoking them (DELETE), including OWNER-only access control, role validation,
 * and 404 for unknown/revoked tokens. Each test authenticates via real bearer tokens
 * issued for tenants/users seeded through {@link PlatformAuthTestSupport} (EN08.3) —
 * tenant and user isolation is exercised with distinct seeded identities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BoardShareControllerIT {

    private static final String BOARDS_PATH = "/whiteboard/boards";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived connection properties to the Spring context, and seeds
     * the {@code public} schema (owned by {@code pivot-core}) before the Spring context
     * and its Flyway run start.
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

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String ownerToken;
    private String editorToken;
    private String otherTenantToken;

    /**
     * Sets up MockMvc and seeds an OWNER + a fellow non-owner tenant member ("editor")
     * plus a user belonging to an entirely separate tenant (for cross-tenant assertions)
     * before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture owner = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ownerToken = owner.rawToken();

        long editorId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                owner.tenantId(), true);
        editorToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                editorId, "active", Instant.now().plusSeconds(3600));

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    /**
     * Helper: creates a board via the API using the given caller's bearer token and
     * returns its UUID string.
     */
    private String createBoard(final String token, final String title) throws Exception {
        MvcResult result = mockMvc.perform(
                        post(BOARDS_PATH)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    // -------------------------------------------------------------------------
    // POST /whiteboard/boards/{boardId}/share
    // -------------------------------------------------------------------------

    /**
     * Given the OWNER, role=EDITOR, no optional fields,
     * when POST /whiteboard/boards/{id}/share,
     * then returns 201 with tokenId, shareLink containing the token, role, expiresAt.
     */
    @Test
    void generateToken_ownerEditor_returns201WithShareLink() throws Exception {
        String boardId = createBoard(ownerToken, "Board A");

        MvcResult result = mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenId").isString())
                .andExpect(jsonPath("$.boardId").value(boardId))
                .andExpect(jsonPath("$.shareLink").isString())
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("shareLink").asText()).contains("/whiteboard/join?token=");
        assertThat(node.get("tokenId").asText()).isNotBlank();
    }

    /**
     * Given VIEWER role and explicit maxUses=5 + ttlDays=14,
     * when POST /whiteboard/boards/{id}/share,
     * then returns 201 with role=VIEWER.
     */
    @Test
    void generateToken_viewerRoleWithOptions_returns201() throws Exception {
        String boardId = createBoard(ownerToken, "Board B");

        mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"VIEWER\",\"maxUses\":5,\"ttlDays\":14}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    /**
     * Given OWNER role in the share request,
     * when POST /whiteboard/boards/{id}/share,
     * then returns 400 with INVALID_ROLE.
     */
    @Test
    void generateToken_ownerRole_returns400InvalidRole() throws Exception {
        String boardId = createBoard(ownerToken, "Board C");

        mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a non-OWNER user in the same tenant,
     * when POST /whiteboard/boards/{id}/share,
     * then returns 403 (board found, but caller is not the owner).
     */
    @Test
    void generateToken_nonOwner_returns403() throws Exception {
        String boardId = createBoard(ownerToken, "Board D");

        mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + editorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Given a board from another tenant,
     * when POST /whiteboard/boards/{id}/share,
     * then returns 404 (cross-tenant anti-enumeration).
     */
    @Test
    void generateToken_crossTenant_returns404() throws Exception {
        String boardId = createBoard(ownerToken, "Board E");

        // otherTenantToken resolves to a user in a genuinely different tenant than the board's
        mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Given role=null in the request body,
     * when POST /whiteboard/boards/{id}/share,
     * then returns 400 (validation).
     */
    @Test
    void generateToken_nullRole_returns400() throws Exception {
        String boardId = createBoard(ownerToken, "Board F");

        mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // DELETE /whiteboard/boards/{boardId}/share/{tokenId}
    // -------------------------------------------------------------------------

    /**
     * Given an existing active token,
     * when DELETE /whiteboard/boards/{id}/share/{tokenId},
     * then returns 204 and subsequent revocation of the same token returns 404.
     */
    @Test
    void revokeToken_activeToken_returns204ThenReturns404OnRetry() throws Exception {
        String boardId = createBoard(ownerToken, "Board G");

        MvcResult shareResult = mockMvc.perform(
                        post(BOARDS_PATH + "/" + boardId + "/share")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String tokenId = objectMapper
                .readTree(shareResult.getResponse().getContentAsString())
                .get("tokenId").asText();

        // First revocation → 204
        mockMvc.perform(
                        delete(BOARDS_PATH + "/" + boardId + "/share/" + tokenId)
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        // Second revocation (already revoked) → 404
        mockMvc.perform(
                        delete(BOARDS_PATH + "/" + boardId + "/share/" + tokenId)
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a random (non-existent) tokenId,
     * when DELETE /whiteboard/boards/{id}/share/{tokenId},
     * then returns 404.
     */
    @Test
    void revokeToken_unknownToken_returns404() throws Exception {
        String boardId = createBoard(ownerToken, "Board H");

        mockMvc.perform(
                        delete(BOARDS_PATH + "/" + boardId + "/share/" + UUID.randomUUID())
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a non-owner user in the same tenant,
     * when DELETE /whiteboard/boards/{id}/share/{tokenId},
     * then returns 403 (board found, but caller is not the owner).
     */
    @Test
    void revokeToken_nonOwner_returns403() throws Exception {
        String boardId = createBoard(ownerToken, "Board I");
        UUID someToken = UUID.randomUUID();

        mockMvc.perform(
                        delete(BOARDS_PATH + "/" + boardId + "/share/" + someToken)
                                .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden());
    }
}
