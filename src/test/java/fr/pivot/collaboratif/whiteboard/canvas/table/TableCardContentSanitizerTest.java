package fr.pivot.collaboratif.whiteboard.canvas.table;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TableCardContentSanitizer} (US08.6.6) — the pure, DB-free part of the
 * TABLE content-sanitisation behaviour also exercised end-to-end by
 * {@code WhiteboardTableCardIT}.
 */
class TableCardContentSanitizerTest {

    private final TableCardContentSanitizer sanitizer = new TableCardContentSanitizer(new ObjectMapper());

    @Test
    void strips_script_tags_from_table_cells() {
        String input = "{\"rows\":[[\"<script>alert(1)</script>\",\"safe\"]]}";
        String sanitized = sanitizer.sanitize(input);
        assertThat(sanitized).doesNotContain("<script", "</script>", "<", ">");
        assertThat(sanitized).contains("alert(1)", "safe");
    }

    @Test
    void strips_malformed_unterminated_tag() {
        String input = "{\"rows\":[[\"<script src=evil.js\"]]}";
        String sanitized = sanitizer.sanitize(input);
        assertThat(sanitized).doesNotContain("<", ">");
    }

    @Test
    void preserves_col_widths_when_present() {
        String input = "{\"rows\":[[\"a\",\"b\"]],\"colW\":[0.3,0.7]}";
        String sanitized = sanitizer.sanitize(input);
        assertThat(sanitized).contains("\"colW\"");
        assertThat(sanitized).contains("0.3");
    }

    @Test
    void leaves_non_table_content_untouched() {
        String plainText = "hello <not a table>";
        assertThat(sanitizer.sanitize(plainText)).isEqualTo(plainText);
    }

    @Test
    void leaves_other_json_shapes_untouched() {
        String svgPath = "{\"d\":\"M0,0 L10,10\"}";
        assertThat(sanitizer.sanitize(svgPath)).isEqualTo(svgPath);
    }

    @Test
    void leaves_null_and_blank_content_untouched() {
        assertThat(sanitizer.sanitize(null)).isNull();
        assertThat(sanitizer.sanitize("")).isEmpty();
    }

    @Test
    void tolerates_non_string_cell_values() {
        String input = "{\"rows\":[[42, true, null]]}";
        String sanitized = sanitizer.sanitize(input);
        assertThat(sanitized).contains("42", "true");
    }
}
