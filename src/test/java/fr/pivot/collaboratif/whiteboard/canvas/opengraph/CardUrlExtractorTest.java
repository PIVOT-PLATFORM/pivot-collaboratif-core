package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import fr.pivot.collaboratif.whiteboard.canvas.CardType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardUrlExtractor} — the URL-detection rule shared by every publisher of
 * {@link CardContentEnrichmentRequestedEvent} (US08.6.5, parity spec §3.4).
 */
class CardUrlExtractorTest {

    @Test
    void link_card_content_is_extracted_as_is() {
        Optional<String> url = CardUrlExtractor.extract(CardType.LINK, "https://example.com/article");
        assertThat(url).contains("https://example.com/article");
    }

    @Test
    void text_card_with_embedded_url_extracts_first_match() {
        Optional<String> url = CardUrlExtractor.extract(
                CardType.TEXT, "Check this out: https://example.com/article and share it");
        assertThat(url).contains("https://example.com/article");
    }

    @Test
    void label_card_with_embedded_url_is_detected() {
        Optional<String> url = CardUrlExtractor.extract(CardType.LABEL, "See http://example.org");
        assertThat(url).contains("http://example.org");
    }

    @Test
    void json_rich_text_envelope_still_exposes_the_literal_url() {
        // JSON.stringify never escapes '/', so a rich-text-formatted TEXT card's content still
        // contains the URL byte-for-byte inside the JSON envelope (see card-format.ts).
        String jsonEnvelope = "{\"text\":\"Link: https://example.com/x\",\"bold\":true}";
        Optional<String> url = CardUrlExtractor.extract(CardType.TEXT, jsonEnvelope);
        assertThat(url).contains("https://example.com/x");
    }

    @Test
    void text_card_without_url_yields_empty() {
        assertThat(CardUrlExtractor.extract(CardType.TEXT, "just a plain note")).isEmpty();
    }

    @Test
    void blank_content_yields_empty() {
        assertThat(CardUrlExtractor.extract(CardType.LINK, "   ")).isEmpty();
        assertThat(CardUrlExtractor.extract(CardType.LINK, null)).isEmpty();
    }

    @Test
    void image_shape_draw_table_types_are_never_enriched_even_with_a_url_looking_content() {
        assertThat(CardUrlExtractor.extract(CardType.IMAGE, "https://cdn.example.com/pic.png")).isEmpty();
        assertThat(CardUrlExtractor.extract(CardType.SHAPE, "https://example.com")).isEmpty();
        assertThat(CardUrlExtractor.extract(CardType.DRAW, "https://example.com")).isEmpty();
        assertThat(CardUrlExtractor.extract(CardType.TABLE, "https://example.com")).isEmpty();
    }

    @Test
    void url_detection_stops_at_whitespace_and_delimiters() {
        Optional<String> url = CardUrlExtractor.extract(
                CardType.TEXT, "Visit <a href=\"https://example.com/a\">https://example.com/a</a> now");
        assertThat(url).contains("https://example.com/a");
        // The full match must not swallow the closing quote/tag delimiter.
        assertThat(url.orElseThrow()).doesNotContain("\"").doesNotContain("<");
    }

    // ---------------------------------------------------------------------
    // US08.6.1 — multi-block TEXT document encoding ({"v":2,"blocks":[…]})
    // ---------------------------------------------------------------------

    @Test
    void block_doc_extracts_url_from_a_block_text_value() {
        String blockDoc = "{\"v\":2,\"blocks\":["
                + "{\"level\":\"title\",\"text\":\"My heading\"},"
                + "{\"level\":\"body\",\"text\":\"Read https://example.com/deep-dive today\",\"bold\":true}"
                + "]}";
        Optional<String> url = CardUrlExtractor.extract(CardType.TEXT, blockDoc);
        assertThat(url).contains("https://example.com/deep-dive");
    }

    @Test
    void block_doc_with_url_in_first_block_wins() {
        String blockDoc = "{\"v\":2,\"blocks\":["
                + "{\"level\":\"body\",\"text\":\"first http://one.example.org here\"},"
                + "{\"level\":\"body\",\"text\":\"second https://two.example.org here\"}"
                + "]}";
        Optional<String> url = CardUrlExtractor.extract(CardType.LABEL, blockDoc);
        assertThat(url).contains("http://one.example.org");
    }

    @Test
    void block_doc_without_any_url_yields_empty() {
        String blockDoc = "{\"v\":2,\"blocks\":["
                + "{\"level\":\"title\",\"text\":\"Just a heading\"},"
                + "{\"level\":\"body\",\"text\":\"and some body text, no links\"}"
                + "]}";
        assertThat(CardUrlExtractor.extract(CardType.TEXT, blockDoc)).isEmpty();
    }

    @Test
    void block_doc_ignores_url_shaped_content_outside_block_text() {
        // A URL sitting in a non-`text` field (here a bogus `href`) must NOT be extracted — only
        // the concatenation of block `text` values is scanned, never the JSON structure.
        String blockDoc = "{\"v\":2,\"blocks\":["
                + "{\"level\":\"body\",\"text\":\"no link in the text\",\"href\":\"https://evil.example.com/x\"}"
                + "]}";
        assertThat(CardUrlExtractor.extract(CardType.TEXT, blockDoc)).isEmpty();
    }

    @Test
    void block_doc_with_empty_blocks_array_yields_empty() {
        assertThat(CardUrlExtractor.extract(CardType.TEXT, "{\"v\":2,\"blocks\":[]}")).isEmpty();
    }

    @Test
    void malformed_block_doc_falls_back_to_plain_text_scan() {
        // Truncated / invalid JSON that still contains a literal URL — must not throw, and must
        // fall back to scanning the raw string, where the URL is still found byte-for-byte.
        String malformed = "{\"v\":2,\"blocks\":[{\"text\":\"see https://example.com/x\" ";
        Optional<String> url = CardUrlExtractor.extract(CardType.TEXT, malformed);
        assertThat(url).contains("https://example.com/x");
    }

    @Test
    void object_without_blocks_key_falls_back_to_raw_scan() {
        // Legacy single-format envelope has no `blocks` array — scanned raw, URL still exposed.
        String legacyEnvelope = "{\"text\":\"Link: https://example.com/legacy\",\"italic\":true}";
        Optional<String> url = CardUrlExtractor.extract(CardType.TEXT, legacyEnvelope);
        assertThat(url).contains("https://example.com/legacy");
    }
}
