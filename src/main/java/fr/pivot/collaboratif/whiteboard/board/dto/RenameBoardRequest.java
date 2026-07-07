package fr.pivot.collaboratif.whiteboard.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for renaming a whiteboard board.
 *
 * <p>The {@code title} field is mandatory and must be between 1 and 100 characters.
 * Validation failures are handled by {@code GlobalExceptionHandler} which returns
 * HTTP 400 with {@code { "code": "INVALID_TITLE" }}.
 */
public record RenameBoardRequest(
        @NotBlank(message = "INVALID_TITLE")
        @Size(min = 1, max = 100, message = "INVALID_TITLE")
        String title) {
}
