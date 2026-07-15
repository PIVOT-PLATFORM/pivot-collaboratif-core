package fr.pivot.collaboratif.whiteboard.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side content validation for {@link CardType#IMAGE} cards (US08.6.4).
 *
 * <p>The reference whiteboard (PouetPouet) stores a card's {@code coverImage} as a
 * data-URL base64 blob with no server-side validation (parity spec §2.7/§6.12) — trusting
 * whatever a client sent, including a declared MIME type never checked against the actual
 * bytes. This is a deliberate PIVOT hardening over that behaviour (flagged explicitly in
 * US08.6.4's Security AC): before a {@code CARD_CREATE}/{@code CARD_UPDATE} of type
 * {@code IMAGE} is persisted, this class
 * <ol>
 *   <li>parses the content as a strict {@code data:image/<subtype>;base64,<payload>} URL,</li>
 *   <li>Base64-decodes the payload and bounds its size ({@link #maxBytes}, configurable via
 *       {@code pivot.whiteboard.card.image.max-bytes}),</li>
 *   <li>sniffs the decoded bytes' real magic-number signature — never trusting the
 *       client-declared subtype — against an allow-list of {@code png}/{@code jpeg}/
 *       {@code gif}/{@code webp}/{@code bmp} (the same five extensions the frontend's
 *       filename-fallback regex recognises, parity spec §4.8). {@code svg} is deliberately
 *       excluded even though it is a valid raster-adjacent image format: an SVG payload can
 *       carry an embedded {@code <script>}, which would execute if ever reflected into a
 *       same-origin context — excluding it entirely removes that class of stored-XSS risk
 *       without needing a separate sanitizer.</li>
 * </ol>
 *
 * <p>On success, returns a <strong>normalised</strong> data URL rebuilt from the sniffed
 * (not the client-declared) subtype — defence in depth against a mismatched declared/actual
 * type. On any failure (malformed structure, bad Base64, oversized payload, unrecognised
 * signature), returns {@link Optional#empty()} and the caller ({@link CanvasActionService})
 * refuses the mutation silently, consistent with every other {@code card:*} refusal path in
 * this Socle.
 *
 * <p>Storage/tenant isolation (also required by the Security AC) is not this class's
 * responsibility: the sanitised data URL is stored inline in {@link Card#getContent()},
 * a column already scoped to {@code board_id}/{@code tenant_id} like every other card field,
 * and read back only through {@link CardRepository} queries that already filter by board and
 * tenant (see that repository's Javadoc) — no separate blob endpoint or URL is introduced
 * that could leak cross-tenant.
 */
@Component
public class ImageCardContentValidator {

    private static final Logger LOG = LoggerFactory.getLogger(ImageCardContentValidator.class);

    /** Matches a strict {@code data:image/<subtype>;base64,<payload>} URL. Subtype is not
     * trusted for the actual validation — only used to reject non-image data URLs early. */
    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("^data:image/[a-zA-Z0-9.+-]+;base64,(?<payload>[A-Za-z0-9+/=\\r\\n]+)$");

    private final int maxBytes;

    /**
     * Creates the validator.
     *
     * @param maxBytes the maximum allowed decoded payload size in bytes, configurable via
     *                 {@code pivot.whiteboard.card.image.max-bytes} (defaults to 5 MiB — the
     *                 Socle's bound on an inline data-URL card, chosen to comfortably fit a
     *                 dimensioned-down screenshot/photo while capping worst-case row size in
     *                 the {@code card} table)
     */
    public ImageCardContentValidator(
            @Value("${pivot.whiteboard.card.image.max-bytes:5242880}") final int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * Validates and normalises an {@code IMAGE} card's {@code content}.
     *
     * @param rawContent the client-supplied content string
     * @return the sanitised data URL (sniffed subtype), or {@link Optional#empty()} if the
     *         content is not a validly-structured, size-bounded, recognised-format image
     */
    public Optional<String> sanitize(final String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            LOG.warn("Rejected IMAGE content: empty or blank");
            return Optional.empty();
        }
        Matcher matcher = DATA_URL_PATTERN.matcher(rawContent.trim());
        if (!matcher.matches()) {
            LOG.warn("Rejected IMAGE content: not a well-formed data:image/*;base64 URL");
            return Optional.empty();
        }
        String payload = matcher.group("payload").replaceAll("\\s", "");
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            LOG.warn("Rejected IMAGE content: invalid Base64 payload");
            return Optional.empty();
        }
        if (decoded.length == 0 || decoded.length > maxBytes) {
            LOG.warn("Rejected IMAGE content: decoded size {} bytes outside bound (max {})",
                    decoded.length, maxBytes);
            return Optional.empty();
        }
        Optional<String> sniffed = sniffSignature(decoded);
        if (sniffed.isEmpty()) {
            LOG.warn("Rejected IMAGE content: no recognised image signature (png/jpeg/gif/webp/bmp)");
            return Optional.empty();
        }
        String normalisedPayload = Base64.getEncoder().encodeToString(decoded);
        return Optional.of("data:image/" + sniffed.get() + ";base64," + normalisedPayload);
    }

    /**
     * Sniffs the real image format from the decoded bytes' magic number, ignoring whatever
     * MIME subtype the client declared in the data URL.
     *
     * @param bytes the decoded payload
     * @return the sniffed subtype ({@code png}/{@code jpeg}/{@code gif}/{@code webp}/
     *         {@code bmp}), or {@link Optional#empty()} if none of the five signatures match
     */
    private Optional<String> sniffSignature(final byte[] bytes) {
        if (matches(bytes, new int[] {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return Optional.of("png");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of("jpeg");
        }
        if (matchesAscii(bytes, "GIF87a") || matchesAscii(bytes, "GIF89a")) {
            return Optional.of("gif");
        }
        if (bytes.length >= 12 && matchesAscii(bytes, "RIFF") && asciiAt(bytes, 8, 4).equals("WEBP")) {
            return Optional.of("webp");
        }
        if (matchesAscii(bytes, "BM")) {
            return Optional.of("bmp");
        }
        return Optional.empty();
    }

    private boolean matches(final byte[] bytes, final int[] signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAscii(final byte[] bytes, final String ascii) {
        return asciiAt(bytes, 0, ascii.length()).equals(ascii);
    }

    private String asciiAt(final byte[] bytes, final int offset, final int length) {
        if (bytes.length < offset + length) {
            return "";
        }
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }
}
