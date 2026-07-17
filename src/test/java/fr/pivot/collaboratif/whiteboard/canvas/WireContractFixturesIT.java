package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardConnectionDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.FieldValueDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantsUpdatePayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN08.5 (Couche 2) — freezes the wire <em>shape</em> (field names, casing, nesting, JSON types)
 * of every DTO/envelope this module broadcasts to the frontend, against committed JSON fixtures
 * under {@code src/test/resources/wire-contract/}.
 *
 * <p>Serialises each representative instance with the real, Spring-managed {@link ObjectMapper}
 * bean (the exact one {@code CanvasActionService}/{@code ParticipantsBroadcastService} use to
 * build every STOMP frame in production — see {@link #objectMapper}), not an ad hoc {@code new
 * ObjectMapper()}, so this test also catches any future change to this application's Jackson
 * configuration (module registration, serialisation features) that would otherwise silently
 * alter the wire shape without any of the DTOs themselves changing. A {@link SpringBootTest} with
 * Testcontainers is required to obtain that bean, matching this repo's existing IT convention
 * (see e.g. {@code WhiteboardCanvasIT}) — Flyway migrations reference {@code public.tenants}/
 * {@code public.users} (owned by {@code pivot-core}), which must exist before the context starts,
 * hence {@link PlatformAuthTestSupport#createPublicSchema}.
 *
 * <p>All fixture values are synthetic (no real tenant/user/board data, no secrets) — see each
 * {@code build*()} factory method below.
 *
 * <p><strong>Regeneration.</strong> Run with {@code -Dwire.contract.regenerate=true} (e.g.
 * {@code mvn test -Dtest=WireContractFixturesIT -Dwire.contract.regenerate=true}) to overwrite
 * every fixture file under {@code src/test/resources/wire-contract/} with the current
 * serialisation of each instance built here, pretty-printed. Always review the resulting {@code
 * git diff} before committing — a regenerated fixture is a wire contract change that the frontend
 * must be told about explicitly, never a silent side effect of running the test suite.
 *
 * <p>No HTTP/WebSocket traffic is exercised here — only the {@link ObjectMapper} bean is needed
 * — so this uses the plain default {@link SpringBootTest} (mock web environment), the same
 * proven pattern as {@code PivotCollaboratifApplicationTests}, rather than a real/random port.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class WireContractFixturesIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies Testcontainer-derived connection properties and seeds the {@code public} schema
     * before the Spring context (and its Flyway migration) starts — same pattern as every other
     * {@code *IT} class in this package.
     *
     * @param registry the dynamic property registry
     * @throws Exception if seeding the {@code public} schema fails
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

    private static final Path FIXTURE_DIR = Paths.get("src", "test", "resources", "wire-contract");
    private static final boolean REGENERATE = Boolean.getBoolean("wire.contract.regenerate");

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * {@link CardDto} — the wire representation of a {@link Card}, sent both nested under
     * {@code "card"} in the {@code card:created} broadcast and flattened in every other
     * {@code card:*}/{@code board:state} shape. Fixture uses a {@code LINK} card with a populated
     * {@code meta} (OpenGraph preview) and a group, so every nullable field is exercised as
     * non-null at least once — the same instance is reused inside the {@code board:state} array
     * by {@link #boardState_matchesCommittedFixture}.
     *
     * @throws IOException if the fixture file cannot be read or (re)written
     */
    @Test
    void cardDto_matchesCommittedFixture() throws IOException {
        assertMatchesFixture("card-dto.json", buildCardDto());
    }

    /**
     * {@link CardConnectionDto} — the wire representation of a {@link CardConnection}, styled
     * with entirely non-default values (shape/arrow/dashed/width/label/color all differ from
     * the fixed defaults applied at creation, US08.7.2) so the fixture actually exercises every
     * field rather than only the creation-time defaults.
     *
     * @throws IOException if the fixture file cannot be read or (re)written
     */
    @Test
    void cardConnectionDto_matchesCommittedFixture() throws IOException {
        assertMatchesFixture("card-connection-dto.json", buildCardConnectionDto());
    }

    /**
     * The {@code board:state} envelope — a {@link BroadcastCanvasMessage} whose {@code data} is
     * the {@code {cards, connections, frames, fields}} map {@code CanvasActionService#handleJoin}
     * broadcasts on JOIN (rejeu d'état initial). {@code frames}/{@code fields} are always
     * broadcast as empty arrays in this Socle (no frame/field feature yet) — captured as such
     * here rather than omitted, since their mere presence (empty array, not absent key) is part
     * of the contract the frontend parses against.
     *
     * @throws IOException if the fixture file cannot be read or (re)written
     */
    @Test
    void boardState_matchesCommittedFixture() throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cards", List.of(buildCardDto()));
        data.put("connections", List.of(buildCardConnectionDto()));
        data.put("frames", List.of());
        data.put("fields", List.of());
        BroadcastCanvasMessage boardState = new BroadcastCanvasMessage(
                "board:state", "c1c1c1c1-0000-4000-8000-000000000001", "1001", data);

        assertMatchesFixture("board-state.json", boardState);
    }

    /**
     * {@link ParticipantsUpdatePayload} — the dedicated presence DTO broadcast to
     * {@code /topic/whiteboard/{boardId}/presence} by {@code ParticipantsBroadcastService} on
     * every JOIN/LEAVE (and on WebSocket-disconnect cleanup). Unlike every other fixture here,
     * this payload is sent as-is (no {@link BroadcastCanvasMessage} envelope, no {@code type}
     * field) — the frontend distinguishes it purely by destination, not by payload shape.
     *
     * @throws IOException if the fixture file cannot be read or (re)written
     */
    @Test
    void participantsUpdatePayload_matchesCommittedFixture() throws IOException {
        ParticipantInfo first = new ParticipantInfo(
                "1001", "Ada Lovelace", "https://example.com/avatar-ada.png", "#E91E63", "EDITOR");
        ParticipantInfo second = new ParticipantInfo(
                "1002", "Grace Hopper", null, "#3F51B5", "VIEWER");
        ParticipantsUpdatePayload payload = new ParticipantsUpdatePayload(List.of(first, second));

        assertMatchesFixture("participants-update.json", payload);
    }

    /**
     * Builds the synthetic {@link CardDto} shared by {@link #cardDto_matchesCommittedFixture}
     * and {@link #boardState_matchesCommittedFixture}.
     *
     * @return a representative {@link CardDto}
     */
    private static CardDto buildCardDto() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", "Example Article Title");
        meta.put("description",
                "A short synthetic description used only for the EN08.5 wire contract fixture.");
        meta.put("image", "https://example.com/preview.png");
        meta.put("siteName", "Example");
        return new CardDto(
                "a1a1a1a1-0000-4000-8000-000000000001",
                "LINK",
                "https://example.com/article",
                meta,
                120.5,
                84.0,
                240.0,
                160.0,
                "#FFEB3B",
                "a1a1a1a1-0000-4000-8000-000000000002",
                "#9C27B0",
                false,
                3,
                List.of(new FieldValueDto(
                        "d1d1d1d1-0000-4000-8000-000000000001",
                        "a1a1a1a1-0000-4000-8000-000000000001",
                        "e1e1e1e1-0000-4000-8000-000000000001",
                        "In progress")));
    }

    /**
     * Builds the synthetic {@link CardConnectionDto} shared by
     * {@link #cardConnectionDto_matchesCommittedFixture} and
     * {@link #boardState_matchesCommittedFixture}.
     *
     * @return a representative {@link CardConnectionDto} with non-default style
     */
    private static CardConnectionDto buildCardConnectionDto() {
        return new CardConnectionDto(
                "b1b1b1b1-0000-4000-8000-000000000001",
                "a1a1a1a1-0000-4000-8000-000000000001",
                "a1a1a1a1-0000-4000-8000-000000000003",
                "depends on",
                "#E91E63",
                "straight",
                "both",
                true,
                4,
                // Deliberately values the legacy fields cannot express: `dotted` has no boolean
                // equivalent, and the two ends differ — `arrow` carried one value for both.
                "dotted",
                "circle",
                "triangle");
    }

    /**
     * Serialises {@code value} with the production {@link ObjectMapper} and asserts it matches
     * the committed fixture at {@code src/test/resources/wire-contract/<fileName>}, compared as
     * parsed JSON trees (so unrelated whitespace/pretty-printing differences never fail the
     * test — only an actual field/value/type difference does). When {@link #REGENERATE} is set,
     * the fixture file is overwritten with the current serialisation first, so the assertion
     * that follows always passes and the file on disk becomes the new baseline.
     *
     * @param fileName the fixture file name under {@code wire-contract/}
     * @param value    the instance to serialise
     * @throws IOException if the fixture file cannot be read or (re)written
     */
    private void assertMatchesFixture(final String fileName, final Object value) throws IOException {
        Path path = FIXTURE_DIR.resolve(fileName);
        if (REGENERATE) {
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            Files.writeString(path, pretty + System.lineSeparator());
        }
        assertThat(Files.exists(path))
                .as("Wire contract fixture missing: %s — regenerate with "
                        + "-Dwire.contract.regenerate=true", path)
                .isTrue();

        JsonNode expected = objectMapper.readTree(Files.readString(path));
        JsonNode actual = objectMapper.valueToTree(value);

        assertThat(actual)
                .as("Serialised %s no longer matches committed fixture %s — if this shape change "
                        + "is intentional, regenerate with -Dwire.contract.regenerate=true, review "
                        + "the diff, and hand it to the pivot-collaboratif-ui agent consuming it",
                        value.getClass().getSimpleName(), path)
                .isEqualTo(expected);
    }
}
