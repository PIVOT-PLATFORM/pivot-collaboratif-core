package fr.pivot.collaboratif.whiteboard.canvas.table;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Defence-in-depth sanitizer for the spreadsheet-grid content of a {@code TABLE} card
 * (US08.6.6), applied unconditionally to every {@code CARD_CREATE}/{@code CARD_UPDATE}
 * content string regardless of the card's actual {@code type} — a TABLE card's content is
 * the only shape this class recognises ({@code {"rows": [["…"]…], "colW"?: […]}}, see the
 * frontend's {@code table.ts}); every other content string (plain text, an SVG path, an
 * image URL…) does not match that shape and is returned unchanged.
 *
 * <p><strong>Why this exists.</strong> The reference frontend's clipboard parser
 * ({@code parseHtmlTable} in {@code table-clipboard.ts}) already reads only
 * {@code Node.textContent} off a pasted {@code <table>} — never {@code innerHTML} — so a
 * legitimate paste through the real UI can never carry markup into a cell in the first
 * place. This class exists for the case the parity spec calls out explicitly (§7): a client
 * that talks directly to the STOMP endpoint (bypassing the frontend's own extraction
 * entirely) and sends raw {@code <script>}/{@code <img onerror=…>} markup as cell text. That
 * payload would otherwise be persisted verbatim and re-broadcast to every other participant.
 *
 * <p><strong>Why tag-stripping, not HTML-entity-escaping.</strong> The rendered cell
 * ({@code board-card.component.html}, {@code {{ cell }}}) is Angular interpolation, which
 * already text-escapes on render — an HTML-escaped {@code &amp;lt;} stored server-side would
 * therefore render literally as the four characters {@code &lt;} instead of the original
 * {@code <}, corrupting perfectly legitimate cell text (e.g. {@code "A & B"} or
 * {@code "x < 10"}). Stripping any {@code <…>} markup instead removes exactly the thing the
 * spec is worried about (an executable/active tag surviving into {@code content}) without
 * mangling plain-text cell values that merely contain {@code&}/{@code<}/{@code>}.
 */
@Component
public class TableCardContentSanitizer {

    private static final String ROWS_FIELD = "rows";
    private static final String COL_W_FIELD = "colW";

    /** Strips any {@code <…>} markup — a minimal, deliberately conservative tag-stripper
     * (not a full HTML parser): sufficient to neutralise an injected tag without altering
     * ordinary text, per the class-level Javadoc's rationale. */
    private static final String TAG_PATTERN = "<[^>]*>";

    private final ObjectMapper objectMapper;

    /**
     * Creates the sanitizer.
     *
     * @param objectMapper the shared Jackson 3 mapper (same instance used across the
     *                      whiteboard canvas package for payload (de)serialisation)
     */
    public TableCardContentSanitizer(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Sanitizes {@code content} if — and only if — it parses as a TABLE card's grid JSON
     * shape ({@code {"rows": [[string…]…]}}); every cell string has any {@code <…>} markup
     * stripped. Any other shape (not JSON, not an object, no array-of-arrays {@code rows}
     * field) is returned completely unchanged — this method is safe to call unconditionally
     * for every card mutation, independently of the card's {@code type}.
     *
     * @param content the raw content string as received from the client
     * @return the sanitized content if it was TABLE-shaped, otherwise {@code content} itself
     */
    public String sanitize(final String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            return content;
        }
        if (!root.isObject() || !root.has(ROWS_FIELD) || !root.get(ROWS_FIELD).isArray()) {
            return content;
        }
        ArrayNode rows = (ArrayNode) root.get(ROWS_FIELD);
        boolean everyElementIsArray = true;
        for (JsonNode row : rows) {
            if (!row.isArray()) {
                everyElementIsArray = false;
                break;
            }
        }
        if (!everyElementIsArray) {
            return content;
        }

        ObjectNode sanitizedRoot = objectMapper.createObjectNode();
        ArrayNode sanitizedRows = objectMapper.createArrayNode();
        for (JsonNode row : rows) {
            ArrayNode sanitizedRow = objectMapper.createArrayNode();
            for (JsonNode cell : row) {
                sanitizedRow.add(sanitizeCell(cell));
            }
            sanitizedRows.add(sanitizedRow);
        }
        sanitizedRoot.set(ROWS_FIELD, sanitizedRows);
        if (root.has(COL_W_FIELD)) {
            sanitizedRoot.set(COL_W_FIELD, root.get(COL_W_FIELD));
        }
        return objectMapper.writeValueAsString(sanitizedRoot);
    }

    /**
     * Sanitizes a single cell value, tolerating a non-string JSON node (number, boolean,
     * null) by falling back to its textual representation — mirrors the frontend's own
     * {@code String(c ?? '')} coercion in {@code parseTableContent}.
     *
     * @param cell the raw cell node
     * @return the tag-stripped cell text
     */
    private String sanitizeCell(final JsonNode cell) {
        String raw = cell.isString() ? cell.asString() : cell.isNull() ? "" : cell.toString();
        // Strip well-formed tags first, then drop any stray angle bracket left over from a
        // malformed/truncated tag (e.g. an unterminated "<script"). Deliberately removed,
        // never escaped to HTML entities — see the class Javadoc on why entity-escaping
        // would corrupt legitimate cell text once rendered through Angular interpolation.
        return raw.replaceAll(TAG_PATTERN, "").replace("<", "").replace(">", "");
    }
}
