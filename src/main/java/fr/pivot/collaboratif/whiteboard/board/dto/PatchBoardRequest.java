package fr.pivot.collaboratif.whiteboard.board.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for updating a whiteboard board's settings (US08.1.4 title rename, extended
 * by US08.2.4 with description/coverImage/maxParticipants/enabledActivities).
 *
 * <p>Every field is optional; an omitted ({@code null}) field leaves the corresponding board
 * attribute unchanged. Renaming ({@code title}) is allowed for the OWNER only, same as
 * before this US — the extended settings fields ({@code description}, {@code coverImage},
 * {@code maxParticipants}, {@code enabledActivities}) are OWNER-only as well (US08.2.4).
 *
 * <p>Validation: {@code title}, if present, must be 1–100 characters (unchanged contract);
 * {@code description}, if present, must be at most 500 characters; {@code maxParticipants},
 * if present, must be at least 1; {@code enabledActivities}, if present, must be a subset of
 * the known activity whitelist (validated at the service layer, since the whitelist is a
 * domain concept, not a simple annotation-friendly constant list).
 */
public record PatchBoardRequest(
        @Size(min = 1, max = 100, message = "INVALID_TITLE")
        String title,
        @Size(max = 500, message = "INVALID_DESCRIPTION")
        String description,
        String coverImage,
        @Min(value = 1, message = "INVALID_MAX_PARTICIPANTS")
        Integer maxParticipants,
        List<String> enabledActivities) {
}
