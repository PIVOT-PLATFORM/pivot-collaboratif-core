package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Assigns a deterministic colour to a participant for the duration of their board session.
 *
 * <p>The palette contains 12 distinct vibrant colours to be aligned with
 * {@code @pivot/design-system} once that package is published (EN17.2). The assignment
 * is deterministic by {@code userId} so that a participant reconnecting to the same board
 * receives the same colour without server-side state.
 *
 * <p>The colour is assigned once at JOIN time and stored in {@link ParticipantMetaStore};
 * it remains stable across the session for coherence between the cursor overlay
 * (US08.3.2c) and the presence panel (US08.5.1).
 */
@Service
public class ColorPaletteService {

    /** 12-colour palette — to be aligned with @pivot/design-system (EN17.2). */
    private static final List<String> PALETTE = List.of(
            "#E91E63", "#9C27B0", "#3F51B5", "#2196F3",
            "#00BCD4", "#009688", "#4CAF50", "#8BC34A",
            "#FFC107", "#FF9800", "#FF5722", "#795548"
    );

    /**
     * Returns a hex colour code deterministically derived from the given user id.
     *
     * <p>The colour is stable: the same {@code userId} always maps to the same colour
     * within the current palette, regardless of session count or timing.
     *
     * @param userId the user's {@code public.users.id}
     * @return a hex colour string from the palette (e.g. {@code "#E91E63"})
     */
    public String colorForUser(final Long userId) {
        int index = Math.floorMod(userId.hashCode(), PALETTE.size());
        return PALETTE.get(index);
    }
}
