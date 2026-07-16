package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import fr.pivot.collaboratif.whiteboard.canvas.CardType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralises the "does this card's content contain a URL to preview" rule (US08.6.5, parity
 * spec §3.4) so every publisher of {@link CardContentEnrichmentRequestedEvent} applies exactly
 * the same regex, regardless of which mutation path (LINK creation, TEXT/LABEL update) fired it.
 *
 * <p>Enrichment applies to:
 * <ul>
 *   <li>{@link CardType#LINK} — the whole (trimmed) {@code content} field <strong>is</strong>
 *       the URL; a regex search is still applied (rather than assuming the field is already a
 *       clean URL) so stray leading/trailing junk pasted alongside it does not break detection.</li>
 *   <li>{@link CardType#TEXT}/{@link CardType#LABEL} — {@code content} is free-form text, or one
 *       of two JSON envelopes the frontend may send (see {@code card-format.ts}):
 *       <ul>
 *         <li>the legacy single-format envelope {@code {"text":"…","bold":…}} — scanned as-is,
 *             since JSON never escapes {@code /} so the URL survives byte-for-byte inside it;</li>
 *         <li>the multi-block document {@code {"v":2,"blocks":[{"level":…,"text":"…"},…]}}
 *             (US08.6.1) — the URL is searched in the <strong>concatenation of the block
 *             {@code text} values only</strong>, never in the JSON keys/structure, so a URL that
 *             happens to sit in a non-text field or in the envelope scaffolding is ignored.</li>
 *       </ul>
 *       In every case the first {@code https?://} substring found is used.</li>
 *   <li>Every other {@link CardType} (IMAGE, SHAPE, DRAW, TABLE) — never enriched, even if their
 *       content happens to contain something URL-shaped (an IMAGE card's content is itself a
 *       URL/data-URL by design — out of scope, see this US's backlog "Hors périmètre").</li>
 * </ul>
 *
 * <p><strong>Defensive by contract:</strong> the block-document contract is owned by the
 * frontend, so {@code content} that <em>looks</em> like a block doc but is malformed (bad JSON,
 * missing/typed-wrong {@code blocks}, non-string {@code text}) must never throw — it silently
 * falls back to scanning the raw {@code content} string, exactly as a plain string would be.
 */
final class CardUrlExtractor {

    /**
     * The exact detection pattern mandated by the parity spec (§3.4, §7) — matches an
     * {@code http}/{@code https} URL up to the first whitespace or HTML/attribute-delimiting
     * character.
     */
    static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']+");

    /**
     * Shared, read-only Jackson mapper used only to recognise and flatten a multi-block TEXT
     * document. {@code ObjectMapper} is thread-safe for read operations once configured, and this
     * class never mutates it — a single static instance is sufficient and avoids per-call
     * allocation on the enrichment hot path.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Separator inserted between concatenated block texts so adjacent blocks never fuse a URL. */
    private static final String BLOCK_TEXT_SEPARATOR = "\n";

    private CardUrlExtractor() {
    }

    /**
     * Extracts the first candidate URL from a card's content, if this card type is eligible for
     * OpenGraph enrichment at all.
     *
     * @param type    the card's typed discriminant
     * @param content the card's content (may be {@code null} or blank)
     * @return the first {@code http}/{@code https} URL substring found, or empty if this card
     *     type is not eligible or no URL is present
     */
    static Optional<String> extract(final CardType type, final String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        if (type != CardType.LINK && type != CardType.TEXT && type != CardType.LABEL) {
            return Optional.empty();
        }
        String scannable = scannableText(content);
        Matcher matcher = URL_PATTERN.matcher(scannable);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    /**
     * Returns the text that should be URL-scanned for a given raw {@code content}: the
     * concatenation of the block {@code text} values when {@code content} is a well-formed
     * multi-block document ({@code {"v":2,"blocks":[…]}}), otherwise the raw {@code content}
     * unchanged (plain string or legacy single-format JSON envelope). Never throws — any parsing
     * anomaly falls back to the raw string.
     *
     * @param content the raw card content
     * @return the string to run {@link #URL_PATTERN} against
     */
    private static String scannableText(final String content) {
        // Cheap structural pre-check: only a JSON object can be a block doc. Skipping the parse
        // for anything that does not start with '{' keeps the common plain-text/URL case allocation
        // -free.
        if (content.charAt(0) != '{') {
            return content;
        }
        try {
            JsonNode root = MAPPER.readTree(content);
            if (root == null || !root.isObject()) {
                return content;
            }
            JsonNode blocks = root.get("blocks");
            if (blocks == null || !blocks.isArray()) {
                // Not a block doc (e.g. the legacy {"text":…,"bold":…} envelope) — scan raw, which
                // still exposes the URL byte-for-byte inside the JSON, preserving prior behaviour.
                return content;
            }
            StringBuilder sb = new StringBuilder(content.length());
            for (JsonNode block : blocks) {
                if (block == null) {
                    continue;
                }
                JsonNode text = block.get("text");
                if (text != null && text.isString()) {
                    if (sb.length() > 0) {
                        sb.append(BLOCK_TEXT_SEPARATOR);
                    }
                    sb.append(text.asString());
                }
            }
            // A block doc with no usable text yields an empty concatenation — that is the correct
            // "no URL" answer, not a reason to fall back to scanning the JSON scaffolding.
            return sb.toString();
        } catch (RuntimeException e) {
            // Malformed JSON (Jackson 3 throws unchecked) — treat as plain text, never propagate.
            return content;
        }
    }
}
