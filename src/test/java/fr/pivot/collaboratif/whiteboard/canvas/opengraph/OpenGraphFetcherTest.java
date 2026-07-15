package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenGraphFetcher}'s pure parsing/capping logic — {@link
 * OpenGraphFetcher#parseAndSanitize} and {@link OpenGraphFetcher#readBounded} are exercised
 * directly with canned HTML/streams, with no network and no {@link SsrfGuard} involved (that is
 * covered separately by {@link DefaultSsrfGuardTest} and the SSRF integration test).
 */
class OpenGraphFetcherTest {

    @Test
    void extracts_all_four_og_fields() {
        String html = """
                <html><head>
                <meta property="og:title" content="Example Article" />
                <meta property="og:description" content="A short summary." />
                <meta property="og:image" content="https://cdn.example.com/pic.png" />
                <meta property="og:site_name" content="Example News" />
                </head><body></body></html>
                """;
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.title()).isEqualTo("Example Article");
        assertThat(meta.description()).isEqualTo("A short summary.");
        assertThat(meta.image()).isEqualTo("https://cdn.example.com/pic.png");
        assertThat(meta.siteName()).isEqualTo("Example News");
    }

    @Test
    void falls_back_to_title_tag_and_meta_description_when_og_tags_absent() {
        String html = "<html><head><title>Fallback Title</title>"
                + "<meta name=\"description\" content=\"Fallback description\"></head></html>";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.title()).isEqualTo("Fallback Title");
        assertThat(meta.description()).isEqualTo("Fallback description");
    }

    @Test
    void description_is_truncated_to_300_characters() {
        String longDescription = "a".repeat(400);
        String html = "<meta property=\"og:description\" content=\"" + longDescription + "\">";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.description()).hasSize(300);
    }

    @Test
    void decodes_html_entities_in_text_fields() {
        String html = "<meta property=\"og:title\" content=\"Bed &amp; Breakfast &mdash; caf&#233;\">";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.title()).startsWith("Bed & Breakfast");
        assertThat(meta.title()).contains("café");
    }

    @Test
    void strips_injected_markup_from_text_fields() {
        String html = "<meta property=\"og:title\" content=\"Hello <script>alert(1)</script> world\">";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.title()).doesNotContain("<script>").doesNotContain("</script>");
        assertThat(meta.title()).isEqualTo("Hello alert(1) world");
    }

    @Test
    void rejects_non_http_image_url() {
        String html = "<meta property=\"og:image\" content=\"javascript:alert(1)\">";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.image()).isNull();
    }

    @Test
    void rejects_relative_image_url() {
        String html = "<meta property=\"og:image\" content=\"/images/pic.png\">";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.image()).isNull();
    }

    @Test
    void accepts_well_formed_https_image_url() {
        String html = "<meta property=\"og:image\" content=\"https://cdn.example.com/a.png\">";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.image()).isEqualTo("https://cdn.example.com/a.png");
    }

    @Test
    void missing_tags_yield_all_null_fields_without_throwing() {
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize("<html><body>No OG tags here</body></html>");
        assertThat(meta.title()).isNull();
        assertThat(meta.description()).isNull();
        assertThat(meta.image()).isNull();
        assertThat(meta.siteName()).isNull();
    }

    @Test
    void first_occurrence_of_a_duplicate_tag_wins() {
        String html = "<meta property=\"og:title\" content=\"First\">"
                + "<meta property=\"og:title\" content=\"Second\">";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.title()).isEqualTo("First");
    }

    @Test
    void read_bounded_caps_at_max_bytes_even_for_a_larger_stream() {
        byte[] oversized = "x".repeat(250_000).getBytes(StandardCharsets.UTF_8);
        String result = OpenGraphFetcher.readBounded(new ByteArrayInputStream(oversized), 100_000);
        assertThat(result).hasSize(100_000);
    }

    @Test
    void read_bounded_returns_the_whole_stream_when_under_the_cap() {
        byte[] small = "hello world".getBytes(StandardCharsets.UTF_8);
        String result = OpenGraphFetcher.readBounded(new ByteArrayInputStream(small), 100_000);
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void parse_and_sanitize_works_on_a_body_truncated_mid_document() {
        // Simulates what happens after readBounded cuts a 100_000-byte response mid-tag: the OG
        // tags positioned early in the document must still parse correctly.
        String html = "<meta property=\"og:title\" content=\"Still parses\">"
                + "<meta property=\"og:description\" content=\"Fine\">"
                + "<!-- truncated mid-";
        OpenGraphMeta meta = OpenGraphFetcher.parseAndSanitize(html);
        assertThat(meta.title()).isEqualTo("Still parses");
        assertThat(meta.description()).isEqualTo("Fine");
    }
}
