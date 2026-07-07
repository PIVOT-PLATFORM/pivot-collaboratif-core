package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.exception.InvalidCanvasElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CanvasElementValidator} covering the strict shape/text/image JSON
 * schema whitelist (US08.4.1).
 */
class CanvasElementValidatorTest {

    private final CanvasElementValidator validator = new CanvasElementValidator(JsonMapper.shared());

    // -------------------------------------------------------------------------
    // SHAPE
    // -------------------------------------------------------------------------

    /**
     * Given a well-formed shape payload with all fields, when validate() is called,
     * then it does not throw.
     */
    @Test
    void validate_shapeWithAllFields_doesNotThrow() {
        String payload = "{\"x\":10,\"y\":20,\"width\":100,\"height\":50,"
                + "\"shapeKind\":\"rectangle\",\"color\":\"#FF00FF\",\"strokeWidth\":2}";

        assertThatCode(() -> validator.validate(CanvasElementType.SHAPE, payload))
                .doesNotThrowAnyException();
    }

    /**
     * Given a shape payload with an unknown shapeKind, when validate() is called,
     * then it throws {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_shapeWithUnknownShapeKind_throws() {
        String payload = "{\"x\":10,\"y\":20,\"width\":100,\"height\":50,\"shapeKind\":\"triangle\"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.SHAPE, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a shape payload with an invalid color format, when validate() is called,
     * then it throws {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_shapeWithInvalidColor_throws() {
        String payload = "{\"x\":10,\"y\":20,\"width\":100,\"height\":50,"
                + "\"shapeKind\":\"rectangle\",\"color\":\"red\"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.SHAPE, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a shape payload with an out-of-range strokeWidth, when validate() is called,
     * then it throws {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_shapeWithOutOfRangeStrokeWidth_throws() {
        String payload = "{\"x\":10,\"y\":20,\"width\":100,\"height\":50,"
                + "\"shapeKind\":\"rectangle\",\"strokeWidth\":999}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.SHAPE, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a shape payload with a field outside the whitelist, when validate() is called,
     * then it throws {@link InvalidCanvasElementException} (strict/closed schema).
     */
    @Test
    void validate_shapeWithUnknownField_throws() {
        String payload = "{\"x\":10,\"y\":20,\"width\":100,\"height\":50,"
                + "\"shapeKind\":\"rectangle\",\"onclick\":\"alert(1)\"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.SHAPE, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    // -------------------------------------------------------------------------
    // TEXT
    // -------------------------------------------------------------------------

    /**
     * Given a well-formed text payload, when validate() is called, then it does not throw.
     */
    @Test
    void validate_textWithValidContent_doesNotThrow() {
        String payload = "{\"x\":0,\"y\":0,\"width\":200,\"height\":40,"
                + "\"content\":\"Hello\",\"fontSize\":16,\"color\":\"#000000\"}";

        assertThatCode(() -> validator.validate(CanvasElementType.TEXT, payload))
                .doesNotThrowAnyException();
    }

    /**
     * Given a text payload with blank content, when validate() is called,
     * then it throws {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_textWithBlankContent_throws() {
        String payload = "{\"x\":0,\"y\":0,\"width\":200,\"height\":40,\"content\":\"   \"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.TEXT, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a text payload whose content contains markup characters, when validate() is
     * called, then it throws {@link InvalidCanvasElementException} (defence against
     * markup injection).
     */
    @Test
    void validate_textWithMarkupCharacters_throws() {
        String payload = "{\"x\":0,\"y\":0,\"width\":200,\"height\":40,"
                + "\"content\":\"<script>alert(1)</script>\"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.TEXT, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a text payload whose content exceeds the maximum length, when validate() is
     * called, then it throws {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_textWithContentTooLong_throws() {
        String longContent = "a".repeat(1001);
        String payload = "{\"x\":0,\"y\":0,\"width\":200,\"height\":40,\"content\":\""
                + longContent + "\"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.TEXT, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    // -------------------------------------------------------------------------
    // IMAGE
    // -------------------------------------------------------------------------

    /**
     * Given a well-formed image payload with a whitelisted internal asset path,
     * when validate() is called, then it does not throw.
     */
    @Test
    void validate_imageWithValidInternalPath_doesNotThrow() {
        String payload = "{\"x\":0,\"y\":0,\"width\":32,\"height\":32,"
                + "\"url\":\"/assets/templates/icon.svg\",\"altText\":\"Icon\"}";

        assertThatCode(() -> validator.validate(CanvasElementType.IMAGE, payload))
                .doesNotThrowAnyException();
    }

    /**
     * Given an image payload with an external absolute URL, when validate() is called,
     * then it throws {@link InvalidCanvasElementException} (only internal asset paths
     * are whitelisted in the Socle).
     */
    @Test
    void validate_imageWithExternalUrl_throws() {
        String payload = "{\"x\":0,\"y\":0,\"width\":32,\"height\":32,"
                + "\"url\":\"https://evil.example.com/x.png\"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.IMAGE, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given an image payload with a protocol-relative URL ("//host/..."), when validate()
     * is called, then it throws {@link InvalidCanvasElementException} — a leading double
     * slash must not be accepted as an internal path, since browsers resolve it against an
     * arbitrary external host just like an absolute URL.
     */
    @Test
    void validate_imageWithProtocolRelativeUrl_throws() {
        String payload = "{\"x\":0,\"y\":0,\"width\":32,\"height\":32,"
                + "\"url\":\"//evil-host/x.png\"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.IMAGE, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given an image payload whose altText is blank, when validate() is called,
     * then it throws {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_imageWithBlankAltText_throws() {
        String payload = "{\"x\":0,\"y\":0,\"width\":32,\"height\":32,"
                + "\"url\":\"/assets/icon.png\",\"altText\":\"   \"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.IMAGE, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    // -------------------------------------------------------------------------
    // Common
    // -------------------------------------------------------------------------

    /**
     * Given a payload missing a required geometry field, when validate() is called for
     * any element type, then it throws {@link InvalidCanvasElementException}.
     *
     * @param type the element type under test
     */
    @ParameterizedTest
    @EnumSource(CanvasElementType.class)
    void validate_missingGeometryField_throws(final CanvasElementType type) {
        String payload = "{\"y\":0,\"width\":10,\"height\":10}";

        assertThatThrownBy(() -> validator.validate(type, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a payload that is not a JSON object (e.g. a JSON array), when validate() is
     * called, then it throws {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_nonObjectPayload_throws() {
        assertThatThrownBy(() -> validator.validate(CanvasElementType.SHAPE, "[1,2,3]"))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a malformed JSON string, when validate() is called, then it throws
     * {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_malformedJson_throws() {
        assertThatThrownBy(() -> validator.validate(CanvasElementType.SHAPE, "{not-json"))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a coordinate field outside the allowed range, when validate() is called,
     * then it throws {@link InvalidCanvasElementException}.
     */
    @Test
    void validate_coordinateOutOfRange_throws() {
        String payload = "{\"x\":-1,\"y\":0,\"width\":10,\"height\":10,"
                + "\"shapeKind\":\"rectangle\"}";

        assertThatThrownBy(() -> validator.validate(CanvasElementType.SHAPE, payload))
                .isInstanceOf(InvalidCanvasElementException.class);
    }
}
