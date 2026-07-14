package fr.pivot.collaboratif.whiteboard.canvas;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShapeStyleSanitizer} — the server-side style content sanitiser for
 * {@link CardType#SHAPE} cards (US08.6.3, correctif §6.4).
 */
class ShapeStyleSanitizerTest {

    private final ShapeStyleSanitizer sanitizer = new ShapeStyleSanitizer(JsonMapper.shared());

    /**
     * Given a well-formed style payload with a whitelisted variant and valid hex colours,
     * when sanitize() is called, then every field is preserved as-is.
     */
    @Test
    void sanitize_wellFormedPayload_preservesAllFields() {
        String result = sanitizer.sanitize("{\"variant\":\"ellipse\",\"fill\":\"#112233\",\"stroke\":\"#445566\"}");

        assertThat(result).contains("\"variant\":\"ellipse\"");
        assertThat(result).contains("\"fill\":\"#112233\"");
        assertThat(result).contains("\"stroke\":\"#445566\"");
    }

    /**
     * Given blank content, when sanitize() is called, then it falls back to the default
     * rectangle variant with no fill/stroke.
     */
    @Test
    void sanitize_blankContent_fallsBackToDefaultVariant() {
        String result = sanitizer.sanitize("");

        assertThat(result).isEqualTo("{\"variant\":\"rectangle\"}");
    }

    /**
     * Given {@code null} content, when sanitize() is called, then it falls back to the
     * default rectangle variant, never throwing.
     */
    @Test
    void sanitize_nullContent_fallsBackToDefaultVariant() {
        String result = sanitizer.sanitize(null);

        assertThat(result).isEqualTo("{\"variant\":\"rectangle\"}");
    }

    /**
     * Given malformed (non-JSON) content, when sanitize() is called, then it falls back to
     * the default variant rather than throwing.
     */
    @Test
    void sanitize_malformedJson_fallsBackToDefaultVariant() {
        String result = sanitizer.sanitize("not-json-at-all{{{");

        assertThat(result).isEqualTo("{\"variant\":\"rectangle\"}");
    }

    /**
     * Given a JSON array (not an object), when sanitize() is called, then it falls back to
     * the default variant.
     */
    @Test
    void sanitize_jsonArrayInsteadOfObject_fallsBackToDefaultVariant() {
        String result = sanitizer.sanitize("[1,2,3]");

        assertThat(result).isEqualTo("{\"variant\":\"rectangle\"}");
    }

    /**
     * Given an out-of-whitelist variant (injection attempt), when sanitize() is called,
     * then the variant is replaced with the default — the malicious value is never
     * persisted (correctif §6.4: the reference whiteboard leaves this unconstrained).
     */
    @Test
    void sanitize_unknownVariant_isReplacedWithDefault() {
        String result = sanitizer.sanitize("{\"variant\":\"<script>alert(1)</script>\"}");

        assertThat(result).isEqualTo("{\"variant\":\"rectangle\"}");
    }

    /**
     * Given a valid variant but an invalid (non-hex) fill colour, when sanitize() is called,
     * then the fill field is dropped, not persisted as-is.
     */
    @Test
    void sanitize_invalidFillColor_isDropped() {
        String result = sanitizer.sanitize("{\"variant\":\"diamond\",\"fill\":\"javascript:alert(1)\"}");

        assertThat(result).contains("\"variant\":\"diamond\"");
        assertThat(result).doesNotContain("fill");
    }

    /**
     * Given a valid variant but an invalid (non-hex) stroke colour, when sanitize() is
     * called, then the stroke field is dropped.
     */
    @Test
    void sanitize_invalidStrokeColor_isDropped() {
        String result = sanitizer.sanitize("{\"variant\":\"line\",\"stroke\":\"not-a-color\"}");

        assertThat(result).contains("\"variant\":\"line\"");
        assertThat(result).doesNotContain("stroke");
    }

    /**
     * Given an unrelated top-level field alongside a valid variant, when sanitize() is
     * called, then the unknown field is dropped — only variant/fill/stroke ever survive.
     */
    @Test
    void sanitize_unknownField_isDropped() {
        String result = sanitizer.sanitize("{\"variant\":\"rectangle\",\"onClick\":\"alert(1)\"}");

        assertThat(result).isEqualTo("{\"variant\":\"rectangle\"}");
    }

    /**
     * Given every whitelisted variant, when sanitize() is called, then each is preserved
     * unchanged.
     */
    @Test
    void sanitize_everyWhitelistedVariant_isPreserved() {
        for (String variant : ShapeStyleSanitizer.ALLOWED_VARIANTS) {
            String result = sanitizer.sanitize("{\"variant\":\"" + variant + "\"}");
            assertThat(result).isEqualTo("{\"variant\":\"" + variant + "\"}");
        }
    }
}
