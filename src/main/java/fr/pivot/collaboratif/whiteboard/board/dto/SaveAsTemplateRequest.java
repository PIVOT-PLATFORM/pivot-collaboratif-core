package fr.pivot.collaboratif.whiteboard.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for saving a board's current canvas content as a new personal template
 * (US08.2.4).
 *
 * <p>{@code name} is mandatory (1–100 characters); {@code description} is optional
 * (up to 500 characters).
 */
public record SaveAsTemplateRequest(
        @NotBlank(message = "INVALID_TEMPLATE_NAME")
        @Size(min = 1, max = 100, message = "INVALID_TEMPLATE_NAME")
        String name,
        @Size(max = 500, message = "INVALID_TEMPLATE_DESCRIPTION")
        String description) {
}
