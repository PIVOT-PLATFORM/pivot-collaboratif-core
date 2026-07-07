package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.exception.InvalidCanvasElementException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates canvas element payloads against a strict JSON schema whitelist (US08.4.1):
 * only {@link CanvasElementType#SHAPE}, {@link CanvasElementType#TEXT}, and
 * {@link CanvasElementType#IMAGE} are accepted, each with an explicit, closed set of
 * fields and bounded values.
 *
 * <p>Applied today to whiteboard template content at board-initialization time (seed
 * data inserted via Flyway, see {@code V1__schema_init.sql}). A validation failure here
 * indicates the seed data itself has drifted from this schema — an internal invariant
 * violation, not a caller input error: the caller only ever supplies {@code templateId},
 * never the element content.
 *
 * <p>The live, user-drawn {@code DRAW} payload (US08.3.1) is not yet validated against
 * this schema — it remains intentionally opaque at the WebSocket layer. Wiring the same
 * whitelist into {@link CanvasActionService} is a natural follow-up but is out of scope
 * for US08.4.1, whose acceptance criteria only require template content to be validated
 * at insertion.
 */
@Component
public class CanvasElementValidator {

    private static final String FIELD_X = "x";
    private static final String FIELD_Y = "y";
    private static final String FIELD_WIDTH = "width";
    private static final String FIELD_HEIGHT = "height";
    private static final String FIELD_COLOR = "color";
    private static final String FIELD_ALT_TEXT = "altText";

    private static final Set<String> SHAPE_KINDS =
            Set.of("rectangle", "ellipse", "line", "diamond");

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private static final Pattern IMAGE_PATH =
            Pattern.compile("^/(?!/)[a-zA-Z0-9/_-]+\\.(png|jpg|jpeg|svg|webp)$");

    private static final Set<String> SHAPE_FIELDS = Set.of(
            FIELD_X, FIELD_Y, FIELD_WIDTH, FIELD_HEIGHT, "shapeKind", FIELD_COLOR, "strokeWidth");

    private static final Set<String> TEXT_FIELDS = Set.of(
            FIELD_X, FIELD_Y, FIELD_WIDTH, FIELD_HEIGHT, "content", "fontSize", FIELD_COLOR);

    private static final Set<String> IMAGE_FIELDS = Set.of(
            FIELD_X, FIELD_Y, FIELD_WIDTH, FIELD_HEIGHT, "url", FIELD_ALT_TEXT);

    private static final double MIN_COORDINATE = 0d;
    private static final double MAX_COORDINATE = 100_000d;
    private static final double MIN_DIMENSION = 1d;
    private static final double MAX_DIMENSION = 100_000d;
    private static final double MIN_STROKE_WIDTH = 0d;
    private static final double MAX_STROKE_WIDTH = 50d;
    private static final double MIN_FONT_SIZE = 8d;
    private static final double MAX_FONT_SIZE = 72d;
    private static final int MAX_TEXT_LENGTH = 1000;
    private static final int MAX_ALT_TEXT_LENGTH = 200;

    private final ObjectMapper objectMapper;

    /**
     * Creates the validator.
     *
     * @param objectMapper the Jackson mapper used to parse the JSON payload string
     */
    public CanvasElementValidator(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validates a canvas element payload against the schema for its declared type.
     *
     * @param type        the whitelisted element kind (shape/text/image)
     * @param payloadJson the JSON payload string to validate
     * @throws InvalidCanvasElementException if the payload is not valid JSON, is not a
     *                                        JSON object, contains a field outside the
     *                                        whitelist for its type, or fails a
     *                                        type-specific constraint
     */
    public void validate(final CanvasElementType type, final String payloadJson) {
        JsonNode node = parse(payloadJson);
        if (!node.isObject()) {
            throw new InvalidCanvasElementException(
                    "Canvas element payload must be a JSON object, type=" + type);
        }
        requireNumberInRange(node, FIELD_X, MIN_COORDINATE, MAX_COORDINATE);
        requireNumberInRange(node, FIELD_Y, MIN_COORDINATE, MAX_COORDINATE);
        requireNumberInRange(node, FIELD_WIDTH, MIN_DIMENSION, MAX_DIMENSION);
        requireNumberInRange(node, FIELD_HEIGHT, MIN_DIMENSION, MAX_DIMENSION);

        Set<String> allowedFields;
        switch (type) {
            case SHAPE -> {
                validateShape(node);
                allowedFields = SHAPE_FIELDS;
            }
            case TEXT -> {
                validateText(node);
                allowedFields = TEXT_FIELDS;
            }
            case IMAGE -> {
                validateImage(node);
                allowedFields = IMAGE_FIELDS;
            }
            default -> throw new InvalidCanvasElementException("Unsupported element type: " + type);
        }
        rejectUnknownFields(node, allowedFields, type);
    }

    /**
     * Parses the payload string into a {@link JsonNode}.
     *
     * @param payloadJson the JSON payload string
     * @return the parsed root node
     * @throws InvalidCanvasElementException if the string is not valid JSON
     */
    private JsonNode parse(final String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (RuntimeException e) {
            throw new InvalidCanvasElementException(
                    "Malformed JSON canvas element payload: " + e.getMessage());
        }
    }

    /**
     * Validates SHAPE-specific fields: a whitelisted {@code shapeKind}, and optional
     * {@code color}/{@code strokeWidth}.
     *
     * @param node the parsed payload
     */
    private void validateShape(final JsonNode node) {
        String shapeKind = requireText(node, "shapeKind");
        if (!SHAPE_KINDS.contains(shapeKind)) {
            throw new InvalidCanvasElementException("Unknown shapeKind: " + shapeKind);
        }
        optionalColor(node);
        optionalNumberInRange(node, "strokeWidth", MIN_STROKE_WIDTH, MAX_STROKE_WIDTH);
    }

    /**
     * Validates TEXT-specific fields: bounded, markup-free {@code content}, and optional
     * {@code fontSize}/{@code color}.
     *
     * @param node the parsed payload
     */
    private void validateText(final JsonNode node) {
        String content = requireText(node, "content");
        if (content.isBlank() || content.length() > MAX_TEXT_LENGTH) {
            throw new InvalidCanvasElementException("Invalid text content length");
        }
        if (content.contains("<") || content.contains(">")) {
            throw new InvalidCanvasElementException(
                    "Text content must not contain markup characters");
        }
        optionalColor(node);
        optionalNumberInRange(node, "fontSize", MIN_FONT_SIZE, MAX_FONT_SIZE);
    }

    /**
     * Validates IMAGE-specific fields: a whitelisted internal asset {@code url}, and an
     * optional, bounded {@code altText}.
     *
     * @param node the parsed payload
     */
    private void validateImage(final JsonNode node) {
        String url = requireText(node, "url");
        if (!IMAGE_PATH.matcher(url).matches()) {
            throw new InvalidCanvasElementException(
                    "Image url must be a whitelisted internal asset path: " + url);
        }
        if (node.has(FIELD_ALT_TEXT)) {
            String altText = requireText(node, FIELD_ALT_TEXT);
            if (altText.isBlank() || altText.length() > MAX_ALT_TEXT_LENGTH) {
                throw new InvalidCanvasElementException("Invalid image altText length");
            }
        }
    }

    /**
     * Validates the optional {@code color} field, if present, against the hex colour pattern.
     *
     * @param node the parsed payload
     */
    private void optionalColor(final JsonNode node) {
        if (node.has(FIELD_COLOR)) {
            String color = requireText(node, FIELD_COLOR);
            if (!HEX_COLOR.matcher(color).matches()) {
                throw new InvalidCanvasElementException("Invalid color format: " + color);
            }
        }
    }

    /**
     * Validates an optional numeric field, if present, is within the given range.
     *
     * @param node  the parsed payload
     * @param field the field name
     * @param min   the inclusive minimum
     * @param max   the inclusive maximum
     */
    private void optionalNumberInRange(
            final JsonNode node, final String field, final double min, final double max) {
        if (node.has(field)) {
            requireNumberInRange(node, field, min, max);
        }
    }

    /**
     * Requires a numeric field within the given inclusive range.
     *
     * @param node  the parsed payload
     * @param field the field name
     * @param min   the inclusive minimum
     * @param max   the inclusive maximum
     * @throws InvalidCanvasElementException if missing, non-numeric, or out of range
     */
    private void requireNumberInRange(
            final JsonNode node, final String field, final double min, final double max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new InvalidCanvasElementException("Missing or non-numeric field: " + field);
        }
        double numericValue = value.asDouble();
        if (numericValue < min || numericValue > max) {
            throw new InvalidCanvasElementException("Field out of range: " + field);
        }
    }

    /**
     * Requires a textual field.
     *
     * @param node  the parsed payload
     * @param field the field name
     * @return the field's text value
     * @throws InvalidCanvasElementException if missing or non-textual
     */
    private String requireText(final JsonNode node, final String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new InvalidCanvasElementException("Missing or non-textual field: " + field);
        }
        return value.asString();
    }

    /**
     * Rejects any top-level field not present in the type's whitelist — enforces a
     * <em>strict</em> (closed) schema rather than merely validating known fields.
     *
     * @param node          the parsed payload
     * @param allowedFields the whitelist of field names for this element type
     * @param type          the element type, used for the error message
     * @throws InvalidCanvasElementException if an unknown field is present
     */
    private void rejectUnknownFields(
            final JsonNode node, final Set<String> allowedFields, final CanvasElementType type) {
        for (String name : node.propertyNames()) {
            if (!allowedFields.contains(name)) {
                throw new InvalidCanvasElementException(
                        "Unknown field '" + name + "' for element type " + type);
            }
        }
    }
}
