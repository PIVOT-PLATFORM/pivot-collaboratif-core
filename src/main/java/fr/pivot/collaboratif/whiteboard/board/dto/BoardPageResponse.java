package fr.pivot.collaboratif.whiteboard.board.dto;

import java.util.List;

/**
 * Paginated response wrapping a list of {@link BoardResponse} items.
 *
 * <p>Returned by {@code GET /whiteboard/boards} to support infinite-scroll and
 * pagination on the frontend.
 *
 * @param boards          the boards on the current page
 * @param totalElements   total number of boards matching the query across all pages
 * @param totalPages      total number of pages at the current page size
 * @param currentPage     zero-based index of the current page
 * @param hasNext         {@code true} if there is at least one more page
 */
public record BoardPageResponse(
        List<BoardResponse> boards,
        long totalElements,
        int totalPages,
        int currentPage,
        boolean hasNext) {
}
