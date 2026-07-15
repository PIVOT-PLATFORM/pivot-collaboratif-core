package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import fr.pivot.collaboratif.whiteboard.canvas.CardType;

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
 *   <li>{@link CardType#TEXT}/{@link CardType#LABEL} — {@code content} is free-form text (or a
 *       rich-text formatting JSON envelope, see the frontend's {@code card-format.ts}); the
 *       first {@code https?://} substring found anywhere in it is used. JSON-encoded content
 *       never escapes {@code /}, so the URL survives byte-for-byte inside the envelope.</li>
 *   <li>Every other {@link CardType} (IMAGE, SHAPE, DRAW, TABLE) — never enriched, even if their
 *       content happens to contain something URL-shaped (an IMAGE card's content is itself a
 *       URL/data-URL by design — out of scope, see this US's backlog "Hors périmètre").</li>
 * </ul>
 */
final class CardUrlExtractor {

    /**
     * The exact detection pattern mandated by the parity spec (§3.4, §7) — matches an
     * {@code http}/{@code https} URL up to the first whitespace or HTML/attribute-delimiting
     * character.
     */
    static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']+");

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
        Matcher matcher = URL_PATTERN.matcher(content);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }
}
