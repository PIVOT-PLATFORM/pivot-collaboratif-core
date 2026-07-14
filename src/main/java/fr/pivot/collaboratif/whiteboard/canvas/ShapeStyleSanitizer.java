package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitises the opaque {@code content} JSON of a {@link CardType#SHAPE} {@link Card} —
 * {@code {variant, fill, stroke}} — against a closed, applicative set of accepted values
 * before persistence (US08.6.3, correctif §6.4).
 *
 * <p>The reference whiteboard (PouetPouet) leaves the equivalent connector style attributes
 * ({@code shape}/{@code arrow}) as free, unconstrained strings in its database (parity spec
 * §6.4) — an injection surface into the SVG/canvas renderer. PIVOT deliberately does not
 * reproduce this defect for {@code SHAPE} cards: {@code variant} is constrained to a known,
 * finite set (mirroring {@link CanvasElementValidator}'s {@code SHAPE_KINDS} whitelist used
 * for template seed content) and {@code fill}/{@code stroke}, if present, must be valid hex
 * colours or they are silently dropped. Any other top-level field is dropped.
 *
 * <p>This is a best-effort <em>sanitisation</em>, not a hard rejection — consistent with the
 * rest of {@link CanvasActionService}'s tolerant handling of malformed card input (never an
 * exception, never a dropped STOMP session). Malformed/empty input falls back to a default
 * {@code {"variant":"rectangle"}} content, matching the reference whiteboard's implicit
 * default shape.
 */
@Component
public class ShapeStyleSanitizer {

    /**
     * Finite, whitelisted set of shape variants — mirrors {@link CanvasElementValidator}'s
     * {@code SHAPE_KINDS} used for template seed content, kept in sync for terminology
     * consistency across the codebase.
     */
    static final Set<String> ALLOWED_VARIANTS = Set.of("rectangle", "ellipse", "diamond", "line");

    private static final String DEFAULT_VARIANT = "rectangle";

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private static final String FIELD_VARIANT = "variant";
    private static final String FIELD_FILL = "fill";
    private static final String FIELD_STROKE = "stroke";

    private final ObjectMapper objectMapper;

    /**
     * Creates the sanitiser.
     *
     * @param objectMapper the Jackson 3 mapper used to parse and rebuild the JSON content
     */
    public ShapeStyleSanitizer(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Sanitises a raw {@code content} string intended for a {@link CardType#SHAPE} card.
     *
     * @param rawContent the raw content string — possibly blank, malformed JSON, carrying an
     *                    out-of-whitelist {@code variant}, invalid colours, or unrelated fields
     * @return a JSON string containing only the whitelisted, valid {@code variant}/
     *         {@code fill}/{@code stroke} fields — never {@code null}, never throws
     */
    public String sanitize(final String rawContent) {
        JsonNode node = parseOrEmpty(rawContent);
        ObjectNode result = objectMapper.createObjectNode();

        String variant = node.path(FIELD_VARIANT).asString(null);
        result.put(FIELD_VARIANT, ALLOWED_VARIANTS.contains(variant) ? variant : DEFAULT_VARIANT);

        putIfValidColor(node, result, FIELD_FILL);
        putIfValidColor(node, result, FIELD_STROKE);

        return objectMapper.writeValueAsString(result);
    }

    /**
     * Copies {@code field} from {@code source} to {@code target} only if present and a
     * valid hex colour string; otherwise the field is silently dropped.
     *
     * @param source the parsed input node
     * @param target the sanitised output node being built
     * @param field  the field name ({@code fill} or {@code stroke})
     */
    private void putIfValidColor(final JsonNode source, final ObjectNode target, final String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isString() && HEX_COLOR.matcher(value.asString()).matches()) {
            target.put(field, value.asString());
        }
    }

    /**
     * Parses {@code rawContent} into a {@link JsonNode}, falling back to an empty object node
     * for blank input, invalid JSON, or a non-object JSON value (array, scalar) — never throws.
     *
     * @param rawContent the raw content string
     * @return the parsed node, or an empty object node as a safe fallback
     */
    private JsonNode parseOrEmpty(final String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(rawContent);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (RuntimeException e) {
            return objectMapper.createObjectNode();
        }
    }
}
