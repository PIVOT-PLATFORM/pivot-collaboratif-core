package fr.pivot.collaboratif.whiteboard.canvas;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN08.5 (Couche 1) — freezes the {@link CanvasEventType} wire vocabulary against a canonical,
 * versioned list committed at {@code src/test/resources/wire-contract/vocabulary.json}.
 *
 * <p>This is the executable half of the wire contract this Enabler introduces: the frontend
 * ({@code pivot-collaboratif-ui}) replicates {@code vocabulary.json} verbatim in its own test
 * suite. Any addition, removal, or rename of a {@link CanvasEventType} constant's name,
 * {@link CanvasEventType#wireIn()}, or {@link CanvasEventType#wireOut()} that is not mirrored in
 * both files fails this test on the backend side (and the frontend's mirror test on that side) —
 * this is exactly the class of silent backend/frontend divergence (casing, punctuation,
 * present/past tense) the Enabler exists to catch. Reacting to a failure here always means
 * updating {@code vocabulary.json} deliberately and handing the diff to the frontend, never
 * "fixing" the test by reverting the enum change.
 *
 * <p>Both {@link CanvasEventType#wireIn()} (what a client sends inbound, resolved by
 * {@link CanvasEventType#fromWire}) and {@link CanvasEventType#wireOut()} (what
 * {@code CanvasActionService#broadcast} emits in {@code BroadcastCanvasMessage#type()}) are
 * captured — see {@link CanvasEventType}'s class Javadoc for why the two differ (present tense
 * in, past tense out) for every {@code CARD_*}/{@code CONNECTION_*} mutation but not for the
 * others.
 *
 * <p><strong>Known gap, out of this test's scope.</strong> Two further wire {@code type} strings
 * exist that are broadcast to clients but do not correspond to any {@link CanvasEventType}
 * constant (so cannot appear in this enum-driven table): {@code "board:state"} (the JOIN-time
 * board snapshot, sent via {@code CanvasActionService}'s raw-string {@code broadcast} overload —
 * see {@link WireContractFixturesIT} for its payload shape) and {@code "board:resetted"}
 * ({@code WhiteboardBroadcastService#broadcastReset}). Both are plain string literals in their
 * respective production classes, not backed by any canonical list — flagged here for whoever
 * next touches either of those literals or extends this vocabulary test to also cover
 * enum-less wire types.
 */
class CanvasEventTypeWireVocabularyTest {

    private static final String VOCABULARY_RESOURCE = "/wire-contract/vocabulary.json";

    /**
     * Verifies that every {@link CanvasEventType} constant's {@code (name, wireIn, wireOut)}
     * triple, in declaration order, matches the committed canonical list exactly.
     *
     * @throws IOException if the canonical vocabulary resource cannot be read
     */
    @Test
    void canvasEventType_matchesCommittedCanonicalVocabulary() throws IOException {
        List<Map<String, Object>> canonical = readCanonicalVocabulary();
        List<Map<String, Object>> actual = Stream.of(CanvasEventType.values())
                .map(CanvasEventTypeWireVocabularyTest::toRow)
                .toList();

        assertThat(actual)
                .as("CanvasEventType's (name, wireIn, wireOut) table no longer matches the "
                        + "committed canonical list at %s — if this addition/removal/rename is "
                        + "intentional, update vocabulary.json deliberately and hand the diff to "
                        + "the pivot-collaboratif-ui agent replicating it", VOCABULARY_RESOURCE)
                .containsExactlyElementsOf(canonical);
    }

    /**
     * Builds the row a single {@link CanvasEventType} constant contributes to the vocabulary
     * table, matching the shape of each entry in {@code vocabulary.json}.
     *
     * @param type the constant to describe
     * @return an ordered {@code name}/{@code wireIn}/{@code wireOut} map
     */
    private static Map<String, Object> toRow(final CanvasEventType type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", type.name());
        row.put("wireIn", type.wireIn());
        row.put("wireOut", type.wireOut());
        return row;
    }

    /**
     * Reads the committed canonical vocabulary list from the classpath. Uses a plain default
     * {@link ObjectMapper} — unlike {@link WireContractFixturesIT}, this is not asserting
     * anything about production DTO serialisation, only parsing a hand-authored, versioned data
     * file, so the Spring-managed bean is not required here.
     *
     * @return the canonical list, one map per entry, in file order
     * @throws IOException if the resource is missing or malformed
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readCanonicalVocabulary() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream(VOCABULARY_RESOURCE)) {
            assertThat(in)
                    .as("Canonical vocabulary resource missing: %s", VOCABULARY_RESOURCE)
                    .isNotNull();
            List<Object> raw = mapper.readValue(in, List.class);
            return raw.stream().map(o -> (Map<String, Object>) o).toList();
        }
    }
}
