package fr.pivot.collaboratif.whiteboard.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for creating a new whiteboard board.
 *
 * <p>The {@code title} field is mandatory and must be between 1 and 100 characters.
 * Validation failures are handled by {@code GlobalExceptionHandler} which returns
 * HTTP 400 with {@code { "code": "INVALID_TITLE" }}.
 *
 * <p>US08.1.9 completes the creation contract beyond the title alone (parity spec §2.2,
 * line 313), mirroring the optional settings fields already accepted by {@code
 * PatchBoardRequest} (US08.2.4): {@code maxParticipants} (strictly positive, nullable),
 * {@code enabledActivities} (subset of the known {@link
 * fr.pivot.collaboratif.whiteboard.board.BoardActivity} whitelist, validated at the service
 * layer since the whitelist is a domain concept), and {@code coverImage} (an opaque string —
 * no server-side upload endpoint exists, parity spec §2.7). Every one of these three fields is
 * optional; a {@code null} value simply leaves the corresponding board attribute at its
 * default (unset).
 */
public record CreateBoardRequest(
        @NotBlank(message = "INVALID_TITLE")
        @Size(min = 1, max = 100, message = "INVALID_TITLE")
        String title,
        @Positive(message = "INVALID_MAX_PARTICIPANTS")
        Integer maxParticipants,
        List<String> enabledActivities,
        String coverImage) {
}
