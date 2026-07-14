package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.canvas.ParticipantMetaStore;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /whiteboard/boards/presence} (US08.1.9, parity §2.2) against
 * a real PostgreSQL + Redis via Testcontainers.
 *
 * <p>Presence is seeded directly through {@link ParticipantMetaStore#put} — the same Redis-backed
 * store {@code CanvasActionService} populates on a real {@code board:join} STOMP frame — rather
 * than driving a full STOMP handshake, keeping these tests focused on the REST aggregation
 * contract itself (already covered end-to-end by {@code WhiteboardPresenceIT} for the STOMP
 * JOIN/LEAVE flow).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BoardPresenceControllerIT {

    private static final String BASE_PATH = "/whiteboard/boards";

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

    @Autowired
    private ParticipantMetaStore participantMetaStore;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        tokenA = fixtureA.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tokenB = fixtureB.rawToken();
    }

    @Test
    void ac08_1_9_14_presence_noConnectedParticipants_returnsEmptyObject() throws Exception {
        createBoardFor(tokenA, "Quiet Board");

        mockMvc.perform(get(BASE_PATH + "/presence")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                    assertThat(body.isEmpty()).isTrue();
                });
    }

    @Test
    void ac08_1_9_15_presence_returnsDedupCountForConnectedBoard() throws Exception {
        String boardId = createBoardFor(tokenA, "Busy Board");
        seedParticipant(tenantA, UUID.fromString(boardId), "1");
        seedParticipant(tenantA, UUID.fromString(boardId), "2");
        // Same userId "1" joining again (e.g. a second tab) overwrites the same HASH field —
        // dedup by userId, not by number of JOINs/sessions.
        seedParticipant(tenantA, UUID.fromString(boardId), "1");

        MvcResult result = mockMvc.perform(get(BASE_PATH + "/presence")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get(boardId).asInt()).isEqualTo(2);
    }

    @Test
    void ac08_1_9_16_presence_noBoardMembership_returnsNoRowForForeignBoard() throws Exception {
        // Board owned by A, with a connected participant — B is not a member and must not see
        // any presence for it (no cross-user/cross-tenant leak of a board B cannot access).
        String boardId = createBoardFor(tokenA, "Private Board");
        seedParticipant(tenantA, UUID.fromString(boardId), "1");

        MvcResult result = mockMvc.perform(get(BASE_PATH + "/presence")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has(boardId)).isFalse();
    }

    @Test
    void ac08_1_9_17_presence_noToken_returns401() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/presence"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ac08_1_9_18_presence_exposesOnlyCountNeverParticipantIdentity() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");
        seedParticipant(tenantA, UUID.fromString(boardId), "1");

        MvcResult result = mockMvc.perform(get(BASE_PATH + "/presence")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        String raw = result.getResponse().getContentAsString();
        assertThat(raw).doesNotContain("displayName").doesNotContain("avatarUrl").doesNotContain("userId");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private void seedParticipant(final long tenantId, final UUID boardId, final String userId) {
        participantMetaStore.put(tenantId, boardId,
                new ParticipantInfo(userId, "User " + userId, null, "#111111", "EDITOR"));
    }
}
