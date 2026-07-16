package fr.pivot.collaboratif.whiteboard.vote;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the vote REST reads ({@code GET /whiteboard/boards/{boardId}/vote/current}
 * and {@code .../vote/last}) against a real PostgreSQL + Redis via Testcontainers.
 *
 * <p>Verifies the rehydration contract the frontend's {@code board.store.ts#loadVote} depends on:
 * the live session on {@code current}, the last closed session on {@code last}, {@code null} when
 * none exists, a 404 for a cross-tenant board (never a 403 leak), and a 401 without a token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class VoteControllerIT {

    private static final String BOARDS_PATH = "/whiteboard/boards";

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
    private VoteSessionRepository voteSessionRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private CardRepository cardRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long userA;
    private String tokenA;
    private String tokenB;

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

    @Test
    void current_returns_active_session_with_its_votes() throws Exception {
        UUID boardId = createBoard(tokenA, "Board with a live vote");
        VoteSession vote = voteSessionRepository.save(new VoteSession(
                boardId, tenantA, 3, 60, Instant.now().plusSeconds(60),
                List.of(String.valueOf(userA)), Instant.now()));
        // A real cast validates the card; the read side only tallies, but the vote's card_id FK
        // still needs a backing card row.
        Card card = cardRepository.save(new Card(boardId, tenantA, CardType.TEXT, "seed", 0, 0, Instant.now()));
        voteRepository.save(new Vote(vote.getId(), card.getId(), userA, Instant.now()));

        MvcResult result = mockMvc.perform(get(votePath(boardId, "current"))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("id").asString()).isEqualTo(vote.getId().toString());
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("votesPerPerson").asInt()).isEqualTo(3);
        assertThat(body.get("votes").size()).isEqualTo(1);
        assertThat(body.get("voterIds").get(0).asString()).isEqualTo(String.valueOf(userA));
    }

    @Test
    void current_returns_empty_body_when_no_active_session() throws Exception {
        UUID boardId = createBoard(tokenA, "Quiet board");

        MvcResult result = mockMvc.perform(get(votePath(boardId, "current"))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isBlank();
    }

    @Test
    void last_returns_the_most_recent_closed_session() throws Exception {
        UUID boardId = createBoard(tokenA, "Board with history");
        VoteSession closed = new VoteSession(
                boardId, tenantA, 2, null, null, List.of(), Instant.now().minusSeconds(300));
        closed.setStatus(VoteStatus.CLOSED);
        closed.setClosedAt(Instant.now().minusSeconds(200));
        voteSessionRepository.save(closed);

        MvcResult result = mockMvc.perform(get(votePath(boardId, "last"))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("id").asString()).isEqualTo(closed.getId().toString());
        assertThat(body.get("status").asString()).isEqualTo("CLOSED");
        assertThat(body.get("closedAt").isNull()).isFalse();
    }

    @Test
    void current_on_cross_tenant_board_returns_404_not_403() throws Exception {
        UUID boardId = createBoard(tokenA, "Tenant A private board");

        mockMvc.perform(get(votePath(boardId, "current"))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void current_without_token_returns_401() throws Exception {
        UUID boardId = createBoard(tokenA, "Board");

        mockMvc.perform(get(votePath(boardId, "current")))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String votePath(final UUID boardId, final String which) {
        return BOARDS_PATH + "/" + boardId + "/vote/" + which;
    }

    private UUID createBoard(final String token, final String title) throws Exception {
        MvcResult result = mockMvc.perform(post(BOARDS_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asString());
    }
}
