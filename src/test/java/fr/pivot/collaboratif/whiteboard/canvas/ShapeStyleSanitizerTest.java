package fr.pivot.collaboratif.whiteboard.canvas;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShapeStyleSanitizer} — the server-side style content sanitiser for
 * {@link CardType#SHAPE} cards (US08.6.3, correctif §6.4), conforming to the pipe-delimited
 * {@code '{kind}|{stroke}|{fill}|{opacity}|{rotation}'} wire format already shipped by the
 * frontend's {@code model/shape.ts}.
 */
class ShapeStyleSanitizerTest {

    private final ShapeStyleSanitizer sanitizer = new ShapeStyleSanitizer();

    /**
     * Given a well-formed style string with a whitelisted kind and valid hex colours, when
     * sanitize() is called, then every field is preserved as-is.
     */
    @Test
    void sanitize_wellFormedPayload_preservesAllFields() {
        String result = sanitizer.sanitize("circle|#112233|#445566|0.5|45");

        assertThat(result).isEqualTo("circle|#112233|#445566|0.5|45");
    }

    /**
     * Given blank content, when sanitize() is called, then it falls back to the default
     * rect/stroke/no-fill/opacity=1/rotation=0 shape.
     */
    @Test
    void sanitize_blankContent_fallsBackToDefaults() {
        String result = sanitizer.sanitize("");

        assertThat(result).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    /**
     * Given {@code null} content, when sanitize() is called, then it falls back to the same
     * defaults, never throwing.
     */
    @Test
    void sanitize_nullContent_fallsBackToDefaults() {
        String result = sanitizer.sanitize(null);

        assertThat(result).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    /**
     * Given a truncated string (missing trailing fields), when sanitize() is called, then the
     * missing fields fall back to their defaults rather than throwing.
     */
    @Test
    void sanitize_truncatedContent_missingFieldsFallBackToDefaults() {
        String result = sanitizer.sanitize("diamond");

        assertThat(result).isEqualTo("diamond|#A5B4FC|none|1|0");
    }

    /**
     * Given an out-of-whitelist kind (injection attempt), when sanitize() is called, then the
     * kind is replaced with the default — the malicious value is never persisted (correctif
     * §6.4: the reference whiteboard leaves this unconstrained).
     */
    @Test
    void sanitize_unknownKind_isReplacedWithDefault() {
        String result = sanitizer.sanitize("<script>alert(1)</script>|#112233|none|1|0");

        assertThat(result).startsWith("rect|");
    }

    /**
     * Given a valid kind but an invalid (non-hex) stroke colour, when sanitize() is called,
     * then the stroke falls back to the default stroke colour, not persisted as-is.
     */
    @Test
    void sanitize_invalidStrokeColor_fallsBackToDefault() {
        String result = sanitizer.sanitize("rect|javascript:alert(1)|none|1|0");

        assertThat(result).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    /**
     * Given a valid kind but an invalid (non-hex, not "none") fill colour, when sanitize() is
     * called, then the fill falls back to "none".
     */
    @Test
    void sanitize_invalidFillColor_fallsBackToNone() {
        String result = sanitizer.sanitize("rect|#A5B4FC|url(javascript:alert(1))|1|0");

        assertThat(result).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    /**
     * Given the literal fill value "none", when sanitize() is called, then it is preserved
     * (it is a legitimate value, not a colour).
     */
    @Test
    void sanitize_noneFill_isPreserved() {
        String result = sanitizer.sanitize("circle|#112233|none|1|0");

        assertThat(result).isEqualTo("circle|#112233|none|1|0");
    }

    /**
     * Given a 3-digit hex colour (shorthand), when sanitize() is called, then it is accepted
     * (matches the frontend's {@code isHexColor}, which allows both 3- and 6-digit hex).
     */
    @Test
    void sanitize_threeDigitHexColor_isAccepted() {
        String result = sanitizer.sanitize("rect|#abc|#def|1|0");

        assertThat(result).isEqualTo("rect|#abc|#def|1|0");
    }

    /**
     * Given a non-numeric opacity, when sanitize() is called, then it falls back to the
     * default opacity (1) rather than persisting the raw garbage value.
     */
    @Test
    void sanitize_nonNumericOpacity_fallsBackToDefault() {
        String result = sanitizer.sanitize("rect|#A5B4FC|none|not-a-number|0");

        assertThat(result).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    /**
     * Given an out-of-range opacity, when sanitize() is called, then it is clamped to [0, 1].
     */
    @Test
    void sanitize_outOfRangeOpacity_isClamped() {
        assertThat(sanitizer.sanitize("rect|#A5B4FC|none|5|0")).isEqualTo("rect|#A5B4FC|none|1|0");
        assertThat(sanitizer.sanitize("rect|#A5B4FC|none|-3|0")).isEqualTo("rect|#A5B4FC|none|0|0");
    }

    /**
     * Given a non-numeric rotation, when sanitize() is called, then it falls back to the
     * default rotation (0).
     */
    @Test
    void sanitize_nonNumericRotation_fallsBackToDefault() {
        String result = sanitizer.sanitize("rect|#A5B4FC|none|1|not-a-number");

        assertThat(result).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    /**
     * Given every whitelisted kind, when sanitize() is called with an otherwise well-formed
     * payload, then each kind is preserved unchanged.
     */
    @Test
    void sanitize_everyWhitelistedKind_isPreserved() {
        for (String kind : ShapeStyleSanitizer.ALLOWED_KINDS) {
            String result = sanitizer.sanitize(kind + "|#112233|none|1|0");
            assertThat(result).isEqualTo(kind + "|#112233|none|1|0");
        }
    }

    /**
     * Given a fractional opacity, when sanitize() is called, then it is formatted without a
     * trailing ".0" for whole numbers but preserves genuine fractional values — matching the
     * frontend's plain {@code Number#toString} serialisation.
     */
    @Test
    void sanitize_fractionalAndWholeNumbers_areFormattedLikeJavaScript() {
        assertThat(sanitizer.sanitize("rect|#A5B4FC|none|1|0")).contains("|1|0");
        assertThat(sanitizer.sanitize("rect|#A5B4FC|none|0.5|90")).contains("|0.5|90");
    }
}
