package fr.pivot.collaboratif.whiteboard.importer;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.BoardField;
import fr.pivot.collaboratif.whiteboard.canvas.BoardFieldRepository;
import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CardConnectionRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardType;
import fr.pivot.collaboratif.whiteboard.canvas.FieldType;
import fr.pivot.collaboratif.whiteboard.canvas.Frame;
import fr.pivot.collaboratif.whiteboard.canvas.FrameRepository;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ImportUndoneBroadcastPayload;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ImportedBroadcastPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WhiteboardImportController} (US08.13.1) exercising the full Spring
 * context against a real PostgreSQL database and Redis provided by Testcontainers.
 *
 * <p>Covers the AC "Tests TI" list: import on an empty board (offset 0), on an occupied board
 * (worked-example offset), case-insensitive field reuse, orphan-connection filtering, undo scoped
 * by {@code boardId} (foreign ids → 0 deleted), fields preserved by undo, VIEWER → 403, and the
 * rate limit's 6th call → 429. The 50&nbsp;MB body → 413 case requires a real HTTP transport
 * (MockMvc's {@code webAppContextSetup} dispatches directly to the {@code DispatcherServlet},
 * bypassing the servlet {@link ImportBodySizeLimitFilter} entirely) and lives in
 * {@link WhiteboardImportBodySizeLimitIT} instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardImportControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived datasource and Redis connection properties to the Spring
     * context and seeds the {@code public} schema before Flyway runs.
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
    private BoardRepository boardRepository;

    @Autowired
    private BoardMemberRepository boardMemberRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardConnectionRepository cardConnectionRepository;

    @Autowired
    private FrameRepository frameRepository;

    @Autowired
    private BoardFieldRepository boardFieldRepository;

    @Autowired
    private ImportRateLimitService rateLimitService;

    /**
     * Spy over the real STOMP template so broadcast destination and payload content can be
     * asserted directly (not just the REST side-effects). The spy delegates to the real
     * {@code convertAndSend} — the {@code SimpleBroker} simply has no subscribers under MockMvc,
     * so the send is a harmless no-op while its arguments are still captured.
     */
    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long ownerIdA;
    private String tokenA;

    /**
     * Sets up MockMvc, seeds a fresh tenant/owner/token fixture, and clears rate-limit counters
     * before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rateLimitService.resetAll();

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        ownerIdA = fixtureA.userId();
        tokenA = fixtureA.rawToken();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Board createBoard(final long tenantId, final long ownerId) {
        Board board = new Board("Import target", tenantId, ownerId, Instant.now());
        boardRepository.save(board);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), ownerId), BoardRole.OWNER, Instant.now()));
        return board;
    }

    private String path(final UUID boardId, final String action) {
        return "/whiteboard/boards/" + boardId + "/import/" + action;
    }

    // -------------------------------------------------------------------------
    // Import — anti-collision offset
    // -------------------------------------------------------------------------

    /**
     * Given an empty board, when a Klaxoon import is posted, then the imported card keeps its
     * original {@code posY} unchanged (offsetY = 0) and the response returns HTTP 201 with the
     * created counts and id lists.
     */
    @Test
    void importOnEmptyBoard_appliesZeroOffset() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String body = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "TEXT", "content": "Hello", "posX": 10, "posY": 20,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false}
                  ],
                  "connections": [],
                  "frames": [],
                  "fields": []
                }
                """;

        MvcResult result = mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cards").value(1))
                .andExpect(jsonPath("$.connections").value(0))
                .andExpect(jsonPath("$.frames").value(0))
                .andExpect(jsonPath("$.cardIds", org.hamcrest.Matchers.hasSize(1)))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID cardId = UUID.fromString(json.get("cardIds").get(0).asText());
        Card card = cardRepository.findById(cardId).orElseThrow();
        assertThat(card.getPosY()).isEqualTo(20.0);
    }

    /**
     * Given an occupied board (existing card at posY=40, height=96 → bottom=136) and an import
     * containing a card at posY=40 (importTop=40), when the import is applied, then
     * offsetY = round(136 + 120 - 40) = 216 and the imported card lands at posY=256 (worked
     * example from the acceptance criteria).
     */
    @Test
    void importOnOccupiedBoard_appliesWorkedExampleOffset() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        Card existing = new Card(board.getId(), tenantA, CardType.TEXT, "Existing", 0, 40, Instant.now());
        existing.setHeight(96);
        cardRepository.save(existing);

        String body = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "TEXT", "content": "Imported", "posX": 5, "posY": 40,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false}
                  ],
                  "connections": [],
                  "frames": [],
                  "fields": []
                }
                """;

        MvcResult result = mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID cardId = UUID.fromString(json.get("cardIds").get(0).asText());
        Card imported = cardRepository.findById(cardId).orElseThrow();
        assertThat(imported.getPosY()).isEqualTo(256.0);
        assertThat(imported.getPosX()).isEqualTo(5.0);
    }

    /**
     * Given two imported cards sharing the same Klaxoon {@code groupKey}, when they are created,
     * then both receive the same freshly server-generated {@code groupId}, distinct from any
     * group already present on the board (never merged with an existing group).
     */
    @Test
    void import_groupsCardsBySharedGroupKeyIntoFreshGroupId() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        UUID existingGroupId = UUID.randomUUID();
        Card preExistingGrouped = new Card(
                board.getId(), tenantA, CardType.TEXT, "Pre-existing", 500, 500, Instant.now());
        preExistingGrouped.setGroupId(existingGroupId);
        cardRepository.save(preExistingGrouped);

        String body = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "TEXT", "content": "A", "posX": 0, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false, "groupKey": "g1"},
                    {"klxId": "k2", "type": "TEXT", "content": "B", "posX": 300, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false, "groupKey": "g1"}
                  ],
                  "connections": [], "frames": [], "fields": []
                }
                """;

        MvcResult result = mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID card1Id = UUID.fromString(json.get("cardIds").get(0).asText());
        UUID card2Id = UUID.fromString(json.get("cardIds").get(1).asText());
        Card card1 = cardRepository.findById(card1Id).orElseThrow();
        Card card2 = cardRepository.findById(card2Id).orElseThrow();

        assertThat(card1.getGroupId()).isNotNull();
        assertThat(card1.getGroupId()).isEqualTo(card2.getGroupId());
        assertThat(card1.getGroupId()).isNotEqualTo(existingGroupId);
    }

    // -------------------------------------------------------------------------
    // Import — custom fields (case-insensitive reuse)
    // -------------------------------------------------------------------------

    /**
     * Given an existing {@code BoardField} named "Priority", when an import's card carries a
     * field value for "priority" (different case), then no new field is created — the existing
     * field is reused and the value attached to it.
     */
    @Test
    void import_reusesExistingBoardFieldCaseInsensitively() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        BoardField existingField = boardFieldRepository.save(new BoardField(
                board.getId(), tenantA, "Priority", null, FieldType.TEXT, null, 0, Instant.now()));

        String body = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "TEXT", "content": "Task", "posX": 0, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false,
                     "fieldValues": [{"field": "priority", "value": "High"}]}
                  ],
                  "connections": [],
                  "frames": [],
                  "fields": []
                }
                """;

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isCreated());

        List<BoardField> fields = boardFieldRepository
                .findAllByBoardIdOrderByOrderAscCreatedAtAsc(board.getId());
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).getId()).isEqualTo(existingField.getId());
    }

    // -------------------------------------------------------------------------
    // Import — orphan connections
    // -------------------------------------------------------------------------

    /**
     * Given a connection referencing a {@code fromKlxId}/{@code toKlxId} not present among the
     * import's cards, when the import is applied, then the connector is silently dropped — not
     * persisted, not counted, no error.
     */
    @Test
    void import_dropsOrphanConnections() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String body = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "TEXT", "content": "A", "posX": 0, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false}
                  ],
                  "connections": [
                    {"fromKlxId": "k1", "toKlxId": "does-not-exist", "shape": "curved",
                     "color": "#000000", "arrow": "none", "label": null, "width": 2, "dashed": false}
                  ],
                  "frames": [],
                  "fields": []
                }
                """;

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connections").value(0))
                .andExpect(jsonPath("$.connectionIds", org.hamcrest.Matchers.hasSize(0)));

        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantA)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Import — security
    // -------------------------------------------------------------------------

    /**
     * Given a VIEWER of the board, when they attempt to import, then the server responds 403.
     */
    @Test
    void import_viewerForbidden_returns403() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        // Same-tenant viewer (cross-tenant access resolves 404, never 403 — anti-enumeration;
        // see import_unknownBoard_returns404) with a plain VIEWER membership row on the board.
        long viewerId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        String viewerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                viewerId, "active", Instant.now().plusSeconds(3600));
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));

        String body = """
                {"cards": [], "connections": [], "frames": [], "fields": []}
                """;

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + viewerToken)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    /**
     * Given a random, non-existent {@code boardId}, when an import is posted, then the server
     * responds 404 (anti-enumeration).
     */
    @Test
    void import_unknownBoard_returns404() throws Exception {
        String body = """
                {"cards": [], "connections": [], "frames": [], "fields": []}
                """;

        mockMvc.perform(post(path(UUID.randomUUID(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a card whose {@code type} is not one of the five importable kinds (e.g. {@code LINK}),
     * when the import is validated, then the server responds 400.
     */
    @Test
    void import_rejectsNonImportableCardType_returns400() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String body = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "LINK", "content": "https://example.com", "posX": 0,
                     "posY": 0, "width": 200, "height": 100, "zIndex": 1, "locked": false}
                  ],
                  "connections": [], "frames": [], "fields": []
                }
                """;

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Import — length-bound validation (name ≤ 120, value ≤ 2000, option ≤ 200)
    // -------------------------------------------------------------------------

    /**
     * Given a top-level field declaration whose {@code name} exceeds 120 characters, when the
     * import is validated, then the server responds 400 before any write.
     */
    @Test
    void import_fieldNameOver120_returns400() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String tooLongName = "n".repeat(121);
        String body = """
                {
                  "cards": [],
                  "connections": [],
                  "frames": [],
                  "fields": [{"name": "%s", "type": "TEXT", "options": null}]
                }
                """.formatted(tooLongName);

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a card field value whose {@code value} exceeds 2000 characters, when the import is
     * validated, then the server responds 400 before any write.
     */
    @Test
    void import_fieldValueOver2000_returns400() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String tooLongValue = "v".repeat(2001);
        String body = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "TEXT", "content": "Task", "posX": 0, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false,
                     "fieldValues": [{"field": "Priority", "value": "%s"}]}
                  ],
                  "connections": [], "frames": [], "fields": []
                }
                """.formatted(tooLongValue);

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a SELECT field declaration one of whose {@code options} exceeds 200 characters, when
     * the import is validated, then the server responds 400 before any write.
     */
    @Test
    void import_fieldOptionOver200_returns400() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String tooLongOption = "o".repeat(201);
        String body = """
                {
                  "cards": [],
                  "connections": [],
                  "frames": [],
                  "fields": [{"name": "Bucket", "type": "SELECT", "options": ["%s"]}]
                }
                """.formatted(tooLongOption);

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Import — STOMP broadcast content (board:imported)
    // -------------------------------------------------------------------------

    /**
     * Given a successful import (2 cards, 1 connector, 1 frame), when it completes, then a
     * {@code board:imported} STOMP message is broadcast on the real topic
     * {@code /topic/whiteboard/{boardId}} carrying the <strong>full created objects</strong>
     * (the card/connection/frame DTOs), not bare ids.
     */
    @Test
    void import_broadcastsBoardImportedWithFullObjectsOnRealTopic() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String body = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "TEXT", "content": "A", "posX": 0, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false},
                    {"klxId": "k2", "type": "TEXT", "content": "B", "posX": 300, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false}
                  ],
                  "connections": [
                    {"fromKlxId": "k1", "toKlxId": "k2", "shape": "curved", "color": "#000000",
                     "arrow": "none", "label": null, "width": 2, "dashed": false}
                  ],
                  "frames": [
                    {"title": "Zone", "posX": 0, "posY": 0, "width": 500, "height": 400}
                  ],
                  "fields": []
                }
                """;

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isCreated());

        BroadcastCanvasMessage msg = captureBroadcast("board:imported", board.getId());
        assertThat(msg).as("board:imported must be broadcast").isNotNull();
        assertThat(msg.data()).isInstanceOf(ImportedBroadcastPayload.class);
        ImportedBroadcastPayload payload = (ImportedBroadcastPayload) msg.data();
        assertThat(payload.cards()).hasSize(2);
        assertThat(payload.connections()).hasSize(1);
        assertThat(payload.frames()).hasSize(1);
        // Full objects, not bare ids: DTO fields are populated.
        assertThat(payload.cards().get(0).id()).isNotBlank();
        assertThat(payload.cards().get(0).content()).isIn("A", "B");
        assertThat(payload.connections().get(0).fromId()).isNotBlank();
        assertThat(payload.frames().get(0).title()).isEqualTo("Zone");
    }

    // -------------------------------------------------------------------------
    // Import — rate limit
    // -------------------------------------------------------------------------

    /**
     * Given more than 5 imports posted to the same board within one minute, when the 6th is
     * posted, then the server responds 429 — the guard is active regardless of profile
     * (US08.13.1 corrects the reference POC, which only enabled it in production).
     */
    @Test
    void import_sixthCallWithinWindow_returns429() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String body = """
                {"cards": [], "connections": [], "frames": [], "fields": []}
                """;

        for (int i = 0; i < ImportRateLimitService.MAX_IMPORTS_PER_WINDOW; i++) {
            mockMvc.perform(post(path(board.getId(), "klaxoon"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenA)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    // -------------------------------------------------------------------------
    // Undo
    // -------------------------------------------------------------------------

    /**
     * Given an undo request whose {@code cardIds} contains an id belonging to a different board,
     * when undo is applied to the target board, then that foreign card is not deleted (0 counted)
     * and remains fully intact on its own board — strict {@code boardId} scoping (IDOR guard).
     */
    @Test
    void undo_scopedByBoardId_foreignIdsYieldZeroDeleted() throws Exception {
        Board boardA = createBoard(tenantA, ownerIdA);
        Board boardB = createBoard(tenantA, ownerIdA);
        Card foreignCard = cardRepository.save(
                new Card(boardB.getId(), tenantA, CardType.TEXT, "Foreign", 0, 0, Instant.now()));

        String undoBody = """
                {"cardIds": ["%s"], "connectionIds": [], "frameIds": []}
                """.formatted(foreignCard.getId());

        mockMvc.perform(post(path(boardA.getId(), "undo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(undoBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards").value(0));

        assertThat(cardRepository.findById(foreignCard.getId())).isPresent();
    }

    /**
     * Given a completed import (cards, a connector between them, and a frame), when the client
     * undoes it with the three returned id lists, then the cards and frame are deleted (counted),
     * the explicit connector delete counts 0 without error (already gone via the cascade of its
     * endpoint card's deletion — cards are deleted before the explicit connector delete), a
     * {@code board:import-undone} round-trip completes with HTTP 200, and every {@code BoardField}
     * the import created is left untouched.
     */
    @Test
    void undo_deletesImportedContent_toleratesCascadedConnections_preservesFields() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String importBody = """
                {
                  "cards": [
                    {"klxId": "k1", "type": "TEXT", "content": "A", "posX": 0, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false,
                     "fieldValues": [{"field": "Status", "value": "Todo"}]},
                    {"klxId": "k2", "type": "TEXT", "content": "B", "posX": 300, "posY": 0,
                     "width": 200, "height": 100, "zIndex": 1, "locked": false}
                  ],
                  "connections": [
                    {"fromKlxId": "k1", "toKlxId": "k2", "shape": "curved", "color": "#000000",
                     "arrow": "none", "label": null, "width": 2, "dashed": false}
                  ],
                  "frames": [
                    {"title": "Zone", "posX": 0, "posY": 0, "width": 500, "height": 400}
                  ],
                  "fields": []
                }
                """;

        MvcResult importResult = mockMvc.perform(post(path(board.getId(), "klaxoon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(importBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connections").value(1))
                .andReturn();

        JsonNode importJson = objectMapper.readTree(importResult.getResponse().getContentAsString());
        List<BoardField> fieldsAfterImport = boardFieldRepository
                .findAllByBoardIdOrderByOrderAscCreatedAtAsc(board.getId());
        assertThat(fieldsAfterImport).hasSize(1);

        String undoBody = objectMapper.writeValueAsString(java.util.Map.of(
                "cardIds", toStringList(importJson.get("cardIds")),
                "connectionIds", toStringList(importJson.get("connectionIds")),
                "frameIds", toStringList(importJson.get("frameIds"))));

        mockMvc.perform(post(path(board.getId(), "undo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(undoBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards").value(2))
                .andExpect(jsonPath("$.frames").value(1))
                // Already removed by the cascade of the endpoint cards' own deletion (cards are
                // deleted before this explicit connector delete runs) — 0, not an error.
                .andExpect(jsonPath("$.connections").value(0));

        assertThat(cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantA))
                .isEmpty();
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantA)).isEmpty();
        assertThat(frameRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantA))
                .isEmpty();
        // BoardField created by the import must survive the undo (never deleted).
        assertThat(boardFieldRepository.findAllByBoardIdOrderByOrderAscCreatedAtAsc(board.getId()))
                .hasSize(1);
    }

    /**
     * Given an undo whose {@code cardIds} exceeds the 10 000-item cap, when validated, then 400.
     */
    @Test
    void undo_exceedingCardIdsCap_returns400() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String undoBody = capBody("cardIds", 10_001, "connectionIds", "frameIds");

        mockMvc.perform(post(path(board.getId(), "undo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(undoBody))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given an undo whose {@code connectionIds} exceeds the 10 000-item cap, when validated, then 400.
     */
    @Test
    void undo_exceedingConnectionIdsCap_returns400() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String undoBody = capBody("connectionIds", 10_001, "cardIds", "frameIds");

        mockMvc.perform(post(path(board.getId(), "undo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(undoBody))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given an undo whose {@code frameIds} exceeds the 1 000-item cap, when validated, then 400.
     */
    @Test
    void undo_exceedingFrameIdsCap_returns400() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        String undoBody = capBody("frameIds", 1_001, "cardIds", "connectionIds");

        mockMvc.perform(post(path(board.getId(), "undo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(undoBody))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a random, non-existent {@code boardId}, when an undo is posted, then the server
     * responds 404 (anti-enumeration), mirroring the import endpoint's own unknown-board
     * behaviour.
     */
    @Test
    void undo_unknownBoard_returns404() throws Exception {
        String undoBody = """
                {"cardIds": [], "connectionIds": [], "frameIds": []}
                """;

        mockMvc.perform(post(path(UUID.randomUUID(), "undo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(undoBody))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Undo — STOMP broadcast content (board:import-undone)
    // -------------------------------------------------------------------------

    /**
     * Given an undo whose id lists contain one real imported card plus one foreign id that is
     * never deleted, when the undo completes, then the {@code board:import-undone} broadcast on
     * {@code /topic/whiteboard/{boardId}} carries the <strong>requested</strong> ids (both, size
     * 2) — not the effectively-deleted ones (only 1 card actually removed).
     */
    @Test
    void undo_broadcastsRequestedIdsNotDeletedOnesOnRealTopic() throws Exception {
        Board board = createBoard(tenantA, ownerIdA);
        Card imported = cardRepository.save(
                new Card(board.getId(), tenantA, CardType.TEXT, "A", 0, 0, Instant.now()));
        String foreignId = UUID.randomUUID().toString();

        String undoBody = """
                {"cardIds": ["%s", "%s"], "connectionIds": [], "frameIds": []}
                """.formatted(imported.getId(), foreignId);

        mockMvc.perform(post(path(board.getId(), "undo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content(undoBody))
                .andExpect(status().isOk())
                // Only the real card was actually deleted (the foreign id resolves nowhere).
                .andExpect(jsonPath("$.cards").value(1));

        BroadcastCanvasMessage msg = captureBroadcast("board:import-undone", board.getId());
        assertThat(msg).as("board:import-undone must be broadcast").isNotNull();
        assertThat(msg.data()).isInstanceOf(ImportUndoneBroadcastPayload.class);
        ImportUndoneBroadcastPayload payload = (ImportUndoneBroadcastPayload) msg.data();
        // Requested ids, not deleted ones: both the real and the foreign id are echoed back.
        assertThat(payload.cardIds()).containsExactlyInAnyOrder(imported.getId().toString(), foreignId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an undo request body where {@code overCapField} holds {@code count} random UUIDs and
     * the other two named fields are empty — used to exercise each list's size cap in isolation.
     */
    private String capBody(
            final String overCapField, final int count, final String emptyA, final String emptyB) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append('"').append(UUID.randomUUID()).append('"');
        }
        return "{\"" + overCapField + "\": [" + ids + "], "
                + "\"" + emptyA + "\": [], \"" + emptyB + "\": []}";
    }

    /**
     * Captures the STOMP message of the given wire {@code type} broadcast for {@code boardId} on
     * the spied {@link SimpMessagingTemplate}, asserting it was sent to the real board topic
     * {@code /topic/whiteboard/{boardId}}. Filters by board id so accumulated invocations from
     * other tests sharing the reused context never match. Returns {@code null} if none was sent.
     */
    private BroadcastCanvasMessage captureBroadcast(final String type, final UUID boardId) {
        ArgumentCaptor<String> destCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(destCaptor.capture(), payloadCaptor.capture());

        String expectedDestination = "/topic/whiteboard/" + boardId;
        List<String> destinations = destCaptor.getAllValues();
        List<Object> payloads = payloadCaptor.getAllValues();
        for (int i = 0; i < payloads.size(); i++) {
            if (payloads.get(i) instanceof BroadcastCanvasMessage msg
                    && type.equals(msg.type())
                    && boardId.toString().equals(msg.boardId())) {
                assertThat(destinations.get(i))
                        .as("broadcast must target the real board topic")
                        .isEqualTo(expectedDestination);
                return msg;
            }
        }
        return null;
    }

    private List<String> toStringList(final JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
