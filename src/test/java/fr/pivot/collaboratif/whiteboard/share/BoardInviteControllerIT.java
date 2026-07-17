package fr.pivot.collaboratif.whiteboard.share;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.notification.NotificationRepository;
import fr.pivot.collaboratif.whiteboard.notification.NotificationType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BoardInviteController} (US08.2.5) exercising the full Spring
 * context against a real PostgreSQL database and Redis provided by Testcontainers.
 *
 * <p>Covers the full acceptance matrix: invitation (201/403/400/404, default VIEWER, EDITOR
 * manager), notification emission ({@code BOARD_SHARED} on new share, {@code ROLE_CHANGED} on role
 * change, none on same role, {@code ROLE_CHANGED} systematic on PATCH, {@code ACCESS_REVOKED} on
 * DELETE), role governance (an EDITOR may neither assign OWNER nor touch an OWNER target), the
 * {@code (shareId, boardId)} IDOR scoping (fix §6.1), and cross-tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BoardInviteControllerIT {

    private static final String BOARDS_PATH = "/whiteboard/boards";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived connection properties and seeds the {@code public} schema before
     * the Spring context and Flyway start.
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
    private WebApplicationContext context;

    @Autowired
    private BoardMemberRepository memberRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private long tenantId;
    private long ownerId;
    private String ownerEmail;
    private String ownerToken;
    private long editorId;
    private String editorToken;
    private UUID boardId;

    /**
     * Seeds an owner (with a known e-mail and token), an EDITOR member, and a board owned by the
     * owner before each test.
     *
     * @throws Exception if seeding or board creation fails
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        tenantId = PlatformAuthTestSupport.seedTenant(url(), user(), pass(), null);
        ownerEmail = "owner-" + UUID.randomUUID() + "@pivot.invalid";
        ownerId = PlatformAuthTestSupport.seedUserWithEmail(url(), user(), pass(), tenantId, ownerEmail, true);
        ownerToken = tokenFor(ownerId);

        editorId = seedUser("editor-" + UUID.randomUUID() + "@pivot.invalid");
        editorToken = tokenFor(editorId);

        boardId = createBoard(ownerToken, "Invite Test Board");
        addMember(boardId, editorId, BoardRole.EDITOR);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String url() {
        return postgres.getJdbcUrl();
    }

    private String user() {
        return postgres.getUsername();
    }

    private String pass() {
        return postgres.getPassword();
    }

    private long seedUser(final String email) throws Exception {
        return PlatformAuthTestSupport.seedUserWithEmail(url(), user(), pass(), tenantId, email, true);
    }

    private String tokenFor(final long userId) throws Exception {
        return PlatformAuthTestSupport.issueToken(
                url(), user(), pass(), userId, "active", Instant.now().plusSeconds(3600));
    }

    private UUID createBoard(final String token, final String title) throws Exception {
        String body = mockMvc.perform(post(BOARDS_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(body).get("id").asText());
    }

    private UUID addMember(final UUID board, final long userId, final BoardRole role) {
        BoardMember saved = memberRepository.save(
                new BoardMember(new BoardMemberId(board, userId), role, Instant.now()));
        return saved.getShareId();
    }

    private String inviteBody(final String email, final String role) {
        if (role == null) {
            return "{\"email\":\"" + email + "\"}";
        }
        return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    private long notifCount(final long recipientId, final NotificationType type) {
        return notificationRepository
                .findByRecipientUserIdAndBoardIdAndTypeOrderByCreatedAtDesc(recipientId, boardId, type)
                .size();
    }

    // ---------------------------------------------------------------------------------------
    // POST /shares/invite
    // ---------------------------------------------------------------------------------------

    @Test
    void invite_ownerDefaultRole_creates201ViewerAndNotifies() throws Exception {
        String email = "invitee-" + UUID.randomUUID() + "@pivot.invalid";
        long inviteeId = seedUser(email);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(inviteeId))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.shareId").isString());

        assertThat(memberRepository.findByIdBoardIdAndIdUserId(boardId, inviteeId)).isPresent();
        assertThat(notifCount(inviteeId, NotificationType.BOARD_SHARED)).isEqualTo(1);
    }

    @Test
    void invite_ownerWithEditorRole_returns201() throws Exception {
        String email = "ed-" + UUID.randomUUID() + "@pivot.invalid";
        seedUser(email);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "EDITOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EDITOR"));
    }

    @Test
    void invite_editorManager_canInviteViewer_returns201() throws Exception {
        String email = "v-" + UUID.randomUUID() + "@pivot.invalid";
        seedUser(email);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "VIEWER")))
                .andExpect(status().isCreated());
    }

    @Test
    void invite_viewerMember_returns403() throws Exception {
        long viewerId = seedUser("viewer-" + UUID.randomUUID() + "@pivot.invalid");
        addMember(boardId, viewerId, BoardRole.VIEWER);
        String viewerToken = tokenFor(viewerId);
        String email = "target-" + UUID.randomUUID() + "@pivot.invalid";
        seedUser(email);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invite_nonMember_returns404() throws Exception {
        AuthFixture stranger = PlatformAuthTestSupport.seedActiveUserWithToken(url(), user(), pass());
        String email = "t-" + UUID.randomUUID() + "@pivot.invalid";
        // stranger is in a different tenant; seed target in stranger's tenant would still 404 board.
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + stranger.rawToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "VIEWER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invite_editorAssignsOwner_returns403() throws Exception {
        String email = "own-" + UUID.randomUUID() + "@pivot.invalid";
        seedUser(email);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invite_unknownEmail_returns404() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("nobody-" + UUID.randomUUID() + "@pivot.invalid", "VIEWER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invite_selfInvitation_returns400() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(ownerEmail, "VIEWER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invite_creatorEmailByEditor_returns400() throws Exception {
        // The creator (owner) is reachable by e-mail; an EDITOR manager inviting them → 400.
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(ownerEmail, "VIEWER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invite_crossTenantEmail_returns404() throws Exception {
        // Same e-mail string, but the matching user lives in another tenant → unknown here.
        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(url(), user(), pass());
        String email = "cross-" + UUID.randomUUID() + "@pivot.invalid";
        PlatformAuthTestSupport.seedUserWithEmail(url(), user(), pass(), otherTenant.tenantId(), email, true);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "VIEWER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invite_reinviteSameRole_noSecondNotification() throws Exception {
        String email = "same-" + UUID.randomUUID() + "@pivot.invalid";
        long inviteeId = seedUser(email);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "VIEWER")))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "VIEWER")))
                .andExpect(status().isCreated());

        assertThat(notifCount(inviteeId, NotificationType.BOARD_SHARED)).isEqualTo(1);
        assertThat(notifCount(inviteeId, NotificationType.ROLE_CHANGED)).isZero();
    }

    @Test
    void invite_reinviteDifferentRole_emitsRoleChanged() throws Exception {
        String email = "chg-" + UUID.randomUUID() + "@pivot.invalid";
        long inviteeId = seedUser(email);

        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "VIEWER")))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "EDITOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EDITOR"));

        assertThat(notifCount(inviteeId, NotificationType.ROLE_CHANGED)).isEqualTo(1);
    }

    @Test
    void invite_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invite_missingAuth_returns401() throws Exception {
        mockMvc.perform(post(BOARDS_PATH + "/" + boardId + "/shares/invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("x@pivot.invalid", "VIEWER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invite_unknownBoard_returns404() throws Exception {
        String email = "nb-" + UUID.randomUUID() + "@pivot.invalid";
        seedUser(email);
        mockMvc.perform(post(BOARDS_PATH + "/" + UUID.randomUUID() + "/shares/invite")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(email, "VIEWER")))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------
    // PATCH /shares/{shareId}
    // ---------------------------------------------------------------------------------------

    @Test
    void patch_ownerChangesRole_returns200AndNotifies() throws Exception {
        long userId = seedUser("p-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.VIEWER);

        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EDITOR"));

        assertThat(notifCount(userId, NotificationType.ROLE_CHANGED)).isEqualTo(1);
    }

    @Test
    void patch_sameRole_stillEmitsRoleChanged() throws Exception {
        long userId = seedUser("ps-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.VIEWER);

        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        assertThat(notifCount(userId, NotificationType.ROLE_CHANGED)).isEqualTo(1);
    }

    @Test
    void patch_editorTargetsOwner_returns403() throws Exception {
        long coOwnerId = seedUser("co-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, coOwnerId, BoardRole.OWNER);

        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patch_editorAssignsOwner_returns403() throws Exception {
        long userId = seedUser("pe-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.VIEWER);

        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patch_ownerPromotesToOwner_returns200() throws Exception {
        long userId = seedUser("promo-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.EDITOR);

        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void patch_shareFromAnotherBoard_returns404() throws Exception {
        UUID otherBoard = createBoard(ownerToken, "Other Board");
        long userId = seedUser("ib-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareOnOther = addMember(otherBoard, userId, BoardRole.VIEWER);

        // Use boardId in the path but a shareId that belongs to otherBoard → 404 (IDOR guard).
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + shareOnOther)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patch_targetsCreatorRow_returns404() throws Exception {
        UUID creatorShareId = memberRepository
                .findByIdBoardIdAndIdUserId(boardId, ownerId).orElseThrow().getShareId();

        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + creatorShareId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patch_viewerManager_returns403() throws Exception {
        long viewerId = seedUser("pv-" + UUID.randomUUID() + "@pivot.invalid");
        addMember(boardId, viewerId, BoardRole.VIEWER);
        String viewerToken = tokenFor(viewerId);
        long userId = seedUser("pt-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.VIEWER);

        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patch_nullRole_returns400() throws Exception {
        long userId = seedUser("pn-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.VIEWER);

        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_unknownShare_returns404() throws Exception {
        mockMvc.perform(patch(BOARDS_PATH + "/" + boardId + "/shares/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------
    // DELETE /shares/{shareId}
    // ---------------------------------------------------------------------------------------

    @Test
    void delete_ownerRevokes_returns204AndNotifies() throws Exception {
        long userId = seedUser("d-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.EDITOR);

        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        assertThat(memberRepository.findByIdBoardIdAndIdUserId(boardId, userId)).isEmpty();
        assertThat(notifCount(userId, NotificationType.ACCESS_REVOKED)).isEqualTo(1);
    }

    @Test
    void delete_editorRevokesViewer_returns204() throws Exception {
        long userId = seedUser("dv-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.VIEWER);

        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_editorRevokesOwner_returns403() throws Exception {
        long coOwnerId = seedUser("do-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, coOwnerId, BoardRole.OWNER);

        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_shareFromAnotherBoard_returns404() throws Exception {
        UUID otherBoard = createBoard(ownerToken, "Other Del Board");
        long userId = seedUser("dib-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareOnOther = addMember(otherBoard, userId, BoardRole.VIEWER);

        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/shares/" + shareOnOther)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        // The share on the other board is untouched.
        assertThat(memberRepository.findByIdBoardIdAndIdUserId(otherBoard, userId)).isPresent();
    }

    @Test
    void delete_targetsCreatorRow_returns404() throws Exception {
        UUID creatorShareId = memberRepository
                .findByIdBoardIdAndIdUserId(boardId, ownerId).orElseThrow().getShareId();

        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/shares/" + creatorShareId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_viewerManager_returns403() throws Exception {
        long viewerId = seedUser("dvm-" + UUID.randomUUID() + "@pivot.invalid");
        addMember(boardId, viewerId, BoardRole.VIEWER);
        String viewerToken = tokenFor(viewerId);
        long userId = seedUser("dt-" + UUID.randomUUID() + "@pivot.invalid");
        UUID shareId = addMember(boardId, userId, BoardRole.VIEWER);

        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/shares/" + shareId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_unknownShare_returns404() throws Exception {
        mockMvc.perform(delete(BOARDS_PATH + "/" + boardId + "/shares/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }
}
