package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.List;

/**
 * {@code data} payload of the {@code board:imported} STOMP broadcast (US08.13.1) sent on
 * {@code /topic/whiteboard/{boardId}} after a successful Klaxoon import — carries the
 * <strong>full created objects</strong> as their existing wire DTOs, not bare ids, so an
 * already-connected client can render the imported content immediately.
 *
 * @param cards       the cards created by the import
 * @param connections the connectors created by the import (orphan-filtered)
 * @param frames      the frames created by the import
 */
public record ImportedBroadcastPayload(
        List<CardDto> cards,
        List<CardConnectionDto> connections,
        List<FrameDto> frames) {
}
