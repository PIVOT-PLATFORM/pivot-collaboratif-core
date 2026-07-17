package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitises the opaque {@code content} of a {@link CardType#SHAPE} {@link Card} against a
 * closed, applicative set of accepted values before persistence (US08.6.3, correctif §6.4).
 *
 * <p><strong>Wire format.</strong> A SHAPE card's {@code content} is the pipe-delimited string
 * {@code '{kind}|{stroke}|{fill}|{opacity}|{rotation}|{diag}'} — <strong>not</strong> JSON. This is
 * the exact encoding already shipped and load-bearing on the frontend
 * (`pivot-collaboratif-ui`'s {@code model/shape.ts}, ported byte-compatible from the PouetPouet
 * reference's {@code board-card-shape.tsx}); this sanitiser conforms to that existing contract
 * rather than inventing a JSON schema the frontend would not understand. {@code kind} ∈
 * {@code {rect, circle, diamond, triangle, line, star}}; {@code fill} is either a hex colour or
 * the literal {@code "none"}; {@code diag} ∈ {@code {tlbr, bltr}} tells which diagonal of its box
 * a {@code line} runs along, and is meaningless for every other kind.
 *
 * <p>The reference whiteboard (PouetPouet) leaves the equivalent connector style attributes
 * ({@code shape}/{@code arrow}) as free, unconstrained strings in its database (parity spec
 * §6.4) — an injection surface into the SVG renderer (a crafted {@code fill}/{@code stroke}
 * could carry a {@code url(javascript:...)}-style value). PIVOT deliberately does not reproduce
 * this defect for {@code SHAPE} cards: {@code kind} is constrained to the finite set above and
 * {@code stroke}/{@code fill}, if not a valid hex colour, fall back to a safe default rather
 * than being persisted as-is.
 *
 * <p>This is a best-effort <em>sanitisation</em>, not a hard rejection — consistent with the
 * rest of {@link CanvasActionService}'s tolerant handling of malformed card input (never an
 * exception, never a dropped STOMP session). Every field independently falls back to the same
 * default the frontend's own {@code parseShape} already uses for a missing/malformed field, so
 * a sanitised value round-trips identically through the frontend's parser.
 */
@Component
public class ShapeStyleSanitizer {

    /** Finite, whitelisted set of shape kinds — mirrors the frontend's {@code ShapeKind}. */
    static final Set<String> ALLOWED_KINDS = Set.of("rect", "circle", "diamond", "triangle", "line", "star");

    /**
     * Finite, whitelisted set of line diagonals — mirrors the frontend's {@code ShapeDiag}. A line
     * is the diagonal of its bounding box, so this bit plus the box reproduces any segment.
     */
    static final Set<String> ALLOWED_DIAGS = Set.of("tlbr", "bltr");

    private static final String DEFAULT_KIND = "rect";
    private static final String DEFAULT_STROKE = "#A5B4FC";
    private static final String NONE = "none";
    private static final double DEFAULT_OPACITY = 1;
    private static final double DEFAULT_ROTATION = 0;
    private static final String DEFAULT_DIAG = "tlbr";

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$");

    private static final int PART_KIND = 0;
    private static final int PART_STROKE = 1;
    private static final int PART_FILL = 2;
    private static final int PART_OPACITY = 3;
    private static final int PART_ROTATION = 4;
    private static final int PART_DIAG = 5;
    private static final int PART_COUNT = 6;

    /**
     * Sanitises a raw {@code content} string intended for a {@link CardType#SHAPE} card.
     *
     * @param rawContent the raw pipe-delimited content string — possibly blank, truncated,
     *                   carrying an out-of-whitelist {@code kind}, invalid colours, or
     *                   non-numeric {@code opacity}/{@code rotation}
     * @return a {@code '{kind}|{stroke}|{fill}|{opacity}|{rotation}|{diag}'} string with every
     *         field validated (or replaced by its safe default) — never {@code null}, never throws
     */
    public String sanitize(final String rawContent) {
        String[] parts = splitFixed(rawContent == null ? "" : rawContent);

        String kind = ALLOWED_KINDS.contains(parts[PART_KIND]) ? parts[PART_KIND] : DEFAULT_KIND;
        String stroke = isHexColor(parts[PART_STROKE]) ? parts[PART_STROKE] : DEFAULT_STROKE;
        String fill = NONE.equals(parts[PART_FILL]) || isHexColor(parts[PART_FILL]) ? parts[PART_FILL] : NONE;
        double opacity = clamp(parseDoubleOrDefault(parts[PART_OPACITY], DEFAULT_OPACITY), 0, 1);
        double rotation = parseDoubleOrDefault(parts[PART_ROTATION], DEFAULT_ROTATION);
        String diag = ALLOWED_DIAGS.contains(parts[PART_DIAG]) ? parts[PART_DIAG] : DEFAULT_DIAG;

        return kind + "|" + stroke + "|" + fill + "|" + formatNumber(opacity) + "|" + formatNumber(rotation)
                + "|" + diag;
    }

    /**
     * Splits the raw content on {@code |} into exactly {@value #PART_COUNT} parts, padding
     * with empty strings for any missing trailing field — every field is then independently
     * validated/defaulted by {@link #sanitize}, so a truncated or over-long input never throws
     * an {@link ArrayIndexOutOfBoundsException}.
     *
     * @param rawContent the raw content string
     * @return exactly {@value #PART_COUNT} parts
     */
    private String[] splitFixed(final String rawContent) {
        String[] split = rawContent.split("\\|", -1);
        String[] fixed = new String[PART_COUNT];
        for (int i = 0; i < PART_COUNT; i++) {
            fixed[i] = i < split.length ? split[i] : "";
        }
        return fixed;
    }

    /**
     * Validates a hex colour string (3 or 6 hex digits after {@code #}), matching the
     * frontend's {@code isHexColor} (`model/colors.ts`).
     *
     * @param value the candidate colour string
     * @return {@code true} if it matches the hex colour pattern
     */
    private boolean isHexColor(final String value) {
        return HEX_COLOR.matcher(value).matches();
    }

    /**
     * Parses a numeric field, falling back to {@code defaultValue} for a blank, non-numeric,
     * or non-finite (NaN/Infinity) value.
     *
     * @param value        the raw field string
     * @param defaultValue the fallback value
     * @return the parsed value, or {@code defaultValue}
     */
    private double parseDoubleOrDefault(final String value, final double defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Clamps a value to the inclusive {@code [min, max]} range.
     *
     * @param value the value to clamp
     * @param min   the inclusive minimum
     * @param max   the inclusive maximum
     * @return the clamped value
     */
    private double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Formats a numeric field the way JavaScript's default {@code Number#toString} would
     * (e.g. {@code 1} rather than {@code 1.0}) so a sanitised value byte-matches what the
     * frontend's own {@code serializeShape} would have produced for the same logical value.
     *
     * @param value the numeric value to format
     * @return the formatted string
     */
    private String formatNumber(final double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.format(Locale.ROOT, "%d", (long) value);
        }
        return String.valueOf(value);
    }
}
