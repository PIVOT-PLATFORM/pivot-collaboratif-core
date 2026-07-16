package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;

import java.util.List;
import java.util.Map;

/**
 * Test-only helpers for reading a {@link BroadcastCanvasMessage}'s polymorphic {@code data}
 * payload, centralising the single unchecked cast per shape.
 *
 * <p>Since fix/whiteboard-wire-contract, {@link BroadcastCanvasMessage#data()} is {@link Object}
 * (a JSON object, a bare string, or an array depending on the broadcast type — see that record's
 * Javadoc). These helpers coerce it to the concrete shape a given assertion expects, keeping the
 * {@code @SuppressWarnings("unchecked")} confined to one place instead of scattered across every IT.
 */
public final class BroadcastPayloads {

    private BroadcastPayloads() {
    }

    /**
     * Views a broadcast's {@code data} as a field-accessible map (object-shaped payloads:
     * {@code card:created}, {@code card:updated}, {@code connection:created}, {@code board:state}…).
     *
     * @param msg the received broadcast
     * @return the {@code data} object as a string-keyed map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(final BroadcastCanvasMessage msg) {
        return (Map<String, Object>) msg.data();
    }

    /**
     * Views a broadcast's {@code data} as a list (array-shaped payloads: {@code board:cursors},
     * {@code board:presence}).
     *
     * @param msg the received broadcast
     * @return the {@code data} object as a list
     */
    @SuppressWarnings("unchecked")
    public static List<Object> list(final BroadcastCanvasMessage msg) {
        return (List<Object>) msg.data();
    }
}
