package fr.pivot.collaboratif.whiteboard.member;

import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BoardMemberController} exercising the full Spring context
 * against a real PostgreSQL database and Redis provided by Testcontainers.
 *
 * <p>Covers US08.2.3 acceptance criteria: listing members (GET), changing roles (PATCH),
 * and removing members (DELETE), including OWNER-only access control and 404/403/400 cases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BoardMemberControllerIT {

    private static final String BOARDS_PATH = "/whiteboard/boards";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived connection properties to the Spring context.
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
    private WebApplicationContext context;

    @Autowired
    private BoardMemberRepository boardMemberRepository;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID ownerId;
    private UUID tenantId;
    private UUID editorId;
    private UUID boardId;

    /**
     * Creates a fresh board (with OWNER member) and adds an EDITOR member before each test.
     * All identifiers are randomised to guarantee test isolation.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        ownerId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        editorId = UUID.randomUUID();

        String createResponse = mockMvc.perform(post(BOARDS_PATH)
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Member Test Board\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse().getContentAsString();

        JsonNode boardJson = mapper.readTree(createResponse);
        boardId = UUID.fromString(boardJson.get("id").asText());

        boardMemberRepository.save(
                new BoardMember(
                        new BoardMemberId(boardId, editorId),
                        BoardRole.EDITOR,
                        Instant.now()));
    }

    // -------------------------------------------------------------------------
    // GET /whiteboard/boards/{boardId}/members — list members
    // -------------------------------------------------------------------------

    @Test
    void listMembers_asOwner_returnsBothMembers() throws Exception {
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].userId").value(editorId.toString()))
                .andExpect(jsonPath("$[1].role").value("EDITOR"));
    }

    @Test
    void listMembers_asMember_returns200() throws Exception {
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("X-Pivot-User-Id", editorId)
                        .header("X-Pivot-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void listMembers_nonMember_returns404() throws Exception {
        UUID stranger = UUID.randomUUID();
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("X-Pivot-User-Id", stranger)
                        .header("X-Pivot-Tenant-Id", tenantId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listMembers_crossTenant_returns404() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", otherTenant))
                .andExpect(status().isNotFound());
    }

    @Test
    void listMembers_missingAuth_returns401() throws Exception {
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PATCH /whiteboard/boards/{boardId}/members/{userId}/role — update role
    // -------------------------------------------------------------------------

    @Test
    void updateRole_ownerChangesEditorToViewer_returns200() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(editorId.toString()))
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void updateRole_nonOwner_returns403() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("X-Pivot-User-Id", editorId)
                        .header("X-Pivot-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"VIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRole_targetIsOwner_returns400() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + ownerId + "/role")
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"EDITOR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_toOwnerRole_returns400() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"OWNER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_nullRole_returns400() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + editorId + "/role")
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_memberNotFound_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/members/" + unknown + "/role")
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"VIEWER\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /whiteboard/boards/{boardId}/members/{userId} — remove member
    // -------------------------------------------------------------------------

    @Test
    void removeMember_ownerRemovesEditor_returns204() throws Exception {
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + editorId)
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId))
                .andExpect(status().isNoContent());
    }

    @Test
    void removeMember_nonOwner_returns403() throws Exception {
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + editorId)
                        .header("X-Pivot-User-Id", editorId)
                        .header("X-Pivot-Tenant-Id", tenantId))
                .andExpect(status().isForbidden());
    }

    @Test
    void removeMember_targetIsOwner_returns400() throws Exception {
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + ownerId)
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeMember_memberNotFound_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/members/" + unknown)
                        .header("X-Pivot-User-Id", ownerId)
                        .header("X-Pivot-Tenant-Id", tenantId))
                .andExpect(status().isNotFound());
    }
}
