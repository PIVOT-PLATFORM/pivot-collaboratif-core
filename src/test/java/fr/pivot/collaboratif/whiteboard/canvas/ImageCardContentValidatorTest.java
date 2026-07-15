package fr.pivot.collaboratif.whiteboard.canvas;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ImageCardContentValidator} (US08.6.4).
 *
 * <p>Covers: real signature sniffing for the five recognised formats, rejection of a spoofed
 * declared MIME subtype that does not match the actual bytes (defence in depth), rejection of
 * an SVG payload (deliberately excluded — a stored-XSS vector via an embedded
 * {@code <script>}), size bounding, and malformed-input tolerance (never an exception).
 */
class ImageCardContentValidatorTest {

    private static final int DEFAULT_MAX_BYTES = 5_242_880;

    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};
    private static final byte[] JPEG_SIGNATURE =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46};
    private static final byte[] GIF_SIGNATURE =
            "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BMP_SIGNATURE =
            {'B', 'M', 0x00, 0x01, 0x02, 0x03};

    private static byte[] webpSignature() {
        byte[] bytes = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }

    private static String dataUrl(final String declaredSubtype, final byte[] bytes) {
        return "data:image/" + declaredSubtype + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private ImageCardContentValidator validator() {
        return new ImageCardContentValidator(DEFAULT_MAX_BYTES);
    }

    @Test
    void accepts_a_real_png_and_normalises_the_subtype() {
        Optional<String> result = validator().sanitize(dataUrl("png", PNG_SIGNATURE));
        assertThat(result).isPresent();
        assertThat(result.get()).startsWith("data:image/png;base64,");
    }

    @Test
    void accepts_a_real_jpeg() {
        Optional<String> result = validator().sanitize(dataUrl("jpeg", JPEG_SIGNATURE));
        assertThat(result).isPresent();
        assertThat(result.get()).startsWith("data:image/jpeg;base64,");
    }

    @Test
    void accepts_a_real_gif() {
        Optional<String> result = validator().sanitize(dataUrl("gif", GIF_SIGNATURE));
        assertThat(result).isPresent();
        assertThat(result.get()).startsWith("data:image/gif;base64,");
    }

    @Test
    void accepts_a_real_webp() {
        Optional<String> result = validator().sanitize(dataUrl("webp", webpSignature()));
        assertThat(result).isPresent();
        assertThat(result.get()).startsWith("data:image/webp;base64,");
    }

    @Test
    void accepts_a_real_bmp() {
        Optional<String> result = validator().sanitize(dataUrl("bmp", BMP_SIGNATURE));
        assertThat(result).isPresent();
        assertThat(result.get()).startsWith("data:image/bmp;base64,");
    }

    @Test
    void ignores_a_spoofed_declared_subtype_and_normalises_to_the_real_sniffed_one() {
        // Client claims PNG, bytes are actually a real JPEG signature — the sniffed (not
        // declared) type wins, both for acceptance and for the normalised output.
        Optional<String> result = validator().sanitize(dataUrl("png", JPEG_SIGNATURE));
        assertThat(result).isPresent();
        assertThat(result.get()).startsWith("data:image/jpeg;base64,");
    }

    @Test
    void rejects_an_svg_payload_even_with_an_image_mime_declared() {
        // SVG can carry an embedded <script> — deliberately excluded from the allow-list
        // (parity spec §4.8's regex also excludes it), regardless of the declared subtype.
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        Optional<String> result = validator().sanitize(dataUrl("svg+xml", svg));
        assertThat(result).isEmpty();
    }

    @Test
    void rejects_plain_text_masquerading_as_an_image_data_url() {
        byte[] text = "just some plain text, not an image".getBytes(StandardCharsets.UTF_8);
        assertThat(validator().sanitize(dataUrl("png", text))).isEmpty();
    }

    @Test
    void rejects_oversized_payloads() {
        ImageCardContentValidator tinyBoundValidator = new ImageCardContentValidator(4);
        assertThat(tinyBoundValidator.sanitize(dataUrl("png", PNG_SIGNATURE))).isEmpty();
    }

    @Test
    void rejects_malformed_base64() {
        assertThat(validator().sanitize("data:image/png;base64,not-valid-base64!!!")).isEmpty();
    }

    @Test
    void rejects_content_that_is_not_a_data_url_at_all() {
        assertThat(validator().sanitize("https://example.com/evil.png")).isEmpty();
    }

    @Test
    void rejects_null_and_blank_content_without_throwing() {
        assertThat(validator().sanitize(null)).isEmpty();
        assertThat(validator().sanitize("")).isEmpty();
        assertThat(validator().sanitize("   ")).isEmpty();
    }

    @Test
    void rejects_an_empty_base64_payload() {
        assertThat(validator().sanitize("data:image/png;base64,")).isEmpty();
    }
}
