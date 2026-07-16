package fr.pivot.collaboratif.whiteboard.canvas;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Whitelisted STOMP message types for whiteboard canvas actions (US08.3.1, extended EN08.4).
 *
 * <p>Any incoming message whose {@code type} field does not resolve via {@link #fromWire}
 * is rejected by {@link WhiteboardActionController} with a WARN log and silently dropped —
 * the session is not closed.
 *
 * <p><strong>Wire naming (EN08.4 recette finding, pivot-collaboratif-core#68).</strong> The
 * frontend ({@code board.store.ts}, mirroring the PouetPouet reference's socket.io
 * {@code emit(event, payload)}/{@code on(event, handler)} vocabulary over this repo's STOMP
 * {@code {type, data}} envelope) sends lowercase, colon-separated, present-tense action names
 * ({@code card:create}, {@code board:join}, {@code board:cursor}...) and listens for
 * <strong>past-tense</strong> broadcast names for card mutations ({@code card:created}, not
 * {@code CARD_CREATE}) — distinct from what it sends. Each constant below carries its incoming
 * wire name ({@link #wireIn()}, what {@link #fromWire} resolves) and outgoing wire name
 * ({@link #wireOut()}, what {@link CanvasActionService#broadcast} emits in the {@code type}
 * field) separately. {@link #fromWire} also still accepts the bare Java enum name
 * (case-insensitive) for any other/future caller that speaks it directly.
 *
 * <p>Persistence behaviour by type:
 * <ul>
 *   <li>{@link #DRAW} — broadcast only in this Socle (EN08.4); free-hand strokes are persisted
 *       as a {@link Card} of {@link CardType#DRAW} via {@link #CARD_CREATE}/{@link #CARD_UPDATE},
 *       not as a {@code canvas_event} row (the append-only {@code DRAW} persistence of US08.3.1
 *       is superseded by the {@link Card} current-state table — see EN08.4's Gate 1 notes).</li>
 *   <li>{@link #CARD_CREATE}, {@link #CARD_MOVE}, {@link #CARD_RESIZE}, {@link #CARD_UPDATE},
 *       {@link #CARD_RECOLOR}, {@link #CARD_DELETE}, {@link #CARD_LAYER}, {@link #CARD_LOCK},
 *       {@link #CARDS_GROUP}, {@link #CARDS_UNGROUP}, {@link #CARDS_GROUP_COLOR} — mutate the
 *       durable {@link Card} table (EN08.4; grouping/locking reuse existing columns, no new
 *       table).</li>
 *   <li>{@link #CONNECTION_CREATE}, {@link #CONNECTION_DELETE}, {@link #CONNECTION_UPDATE} —
 *       mutate the durable {@link CardConnection} table (US08.7.1, US08.7.2).</li>
 *   <li>{@link #JOIN}, {@link #LEAVE}, {@link #CURSOR_MOVE}, {@link #UNDO}, {@link #RESET},
 *       {@link #CARD_EDITING} — ephemeral, broadcast only, never persisted.</li>
 * </ul>
 */
public enum CanvasEventType {
    /** User joins the canvas room; server assigns colour, emits PARTICIPANTS_UPDATE, and
     * broadcasts the board's current {@link Card} list to the room (see
     * {@code CanvasActionService#handleJoin}). Outgoing name kept as the bare enum name — no
     * frontend consumer needs it renamed, only the incoming wire vocabulary was wrong. */
    JOIN("board:join", "JOIN"),
    /** User leaves the canvas room; server removes metadata and emits PARTICIPANTS_UPDATE. */
    LEAVE("board:leave", "LEAVE"),
    /** Drawing action broadcast (visual feedback during a stroke); not persisted directly —
     * see the class-level Javadoc for how free-hand strokes are actually persisted (EN08.4). */
    DRAW("DRAW"),
    /** Cursor position update; broadcast only, never persisted (high-frequency, low-value).
     * Incoming as {@code board:cursor} (a single {@code {x, y}}); rebroadcast as
     * {@code board:cursors} carrying a one-element batch enriched with the mover's
     * {@code userId}/{@code name}/{@code avatar} — the frontend ({@code board.store.ts}) listens
     * for {@code board:cursors} with a {@code [{userId, name, avatar, x, y}]} array and merges by
     * {@code userId}. */
    CURSOR_MOVE("board:cursor", "board:cursors"),
    /** Undo request; broadcast for visual sync, stack logic delegated to US08.3.3. */
    UNDO("UNDO"),
    /** Board canvas reset (US08.2.4): broadcast only, never persisted — the triggering
     * {@code POST .../reset} call already deleted the underlying DRAW events server-side. */
    RESET("RESET"),
    /** Creates a new {@link Card} (EN08.4). */
    CARD_CREATE("card:create", "card:created"),
    /** Moves an existing {@link Card}; refused if locked (EN08.4). */
    CARD_MOVE("card:move", "card:moved"),
    /** Resizes an existing {@link Card}; refused if locked (EN08.4). */
    CARD_RESIZE("card:resize", "card:resized"),
    /** Updates an existing {@link Card}'s content; refused if locked (EN08.4). */
    CARD_UPDATE("card:update", "card:updated"),
    /** Recolors an existing {@link Card}; refused if locked (EN08.4). */
    CARD_RECOLOR("card:recolor", "card:recolored"),
    /** Deletes an existing {@link Card}; refused if locked, checked explicitly by the caller
     * before the delete itself (EN08.4, guard added by fix/EN08.4). */
    CARD_DELETE("card:delete", "card:deleted"),
    /** Changes an existing {@link Card}'s Z-order layer; not blocked by {@code locked} (EN08.4). */
    CARD_LAYER("card:layer", "card:layered"),
    /** Locks or unlocks a set of {@link Card}s (the {@code locked} column already exists, EN08.4);
     * echoes {@code cards:locked} carrying {@code {ids, locked}} to the whole room. */
    CARD_LOCK("card:lock", "cards:locked"),
    /** Groups a set of {@link Card}s under a fresh server-assigned {@code group_id}; echoes
     * {@code cards:grouped} carrying {@code {cardIds, groupId}}. */
    CARDS_GROUP("cards:group", "cards:grouped"),
    /** Ungroups every {@link Card} of a given group (clears {@code group_id}/{@code group_color});
     * echoes {@code cards:ungrouped} carrying the bare {@code groupId} string. */
    CARDS_UNGROUP("cards:ungroup", "cards:ungrouped"),
    /** Recolors a group's outline ({@code group_color}); echoes {@code cards:group-colored}
     * carrying {@code {groupId, color}}. */
    CARDS_GROUP_COLOR("cards:group-color", "cards:group-colored"),
    /** Ephemeral concurrent-editing signal for a {@link Card}; never persisted. Echoes
     * {@code card:editing} carrying {@code {cardId, userId, name, editing}} enriched with the
     * emitter's server-side identity. */
    CARD_EDITING("card:editing", "card:editing"),
    /** Creates a new {@link CardConnection} linking two {@link Card}s (US08.7.1). */
    CONNECTION_CREATE("connection:create", "connection:created"),
    /** Deletes an existing {@link CardConnection}; tolerant of an already-cascaded id (US08.7.1). */
    CONNECTION_DELETE("connection:delete", "connection:deleted"),
    /** Applies a partial style patch to an existing {@link CardConnection} — only the fields
     * present in the incoming payload are mutated (US08.7.2). */
    CONNECTION_UPDATE("connection:update", "connection:updated"),
    /** Creates a new {@link Frame} container (EN08, Frames); echoes the full flat frame. */
    FRAME_CREATE("frame:create", "frame:created"),
    /** Moves an existing {@link Frame}; echoes the full flat frame (EN08, Frames). */
    FRAME_MOVE("frame:move", "frame:moved"),
    /** Resizes an existing {@link Frame} (and optionally moves it); echoes the full flat frame. */
    FRAME_RESIZE("frame:resize", "frame:resized"),
    /** Updates an existing {@link Frame}'s title/active/color (partial patch); echoes the full flat frame. */
    FRAME_UPDATE("frame:update", "frame:updated"),
    /** Deletes an existing {@link Frame}; echoes the bare id string (post-#84 wire envelope,
     * mirrors card:deleted/connection:deleted — see handleFrameDelete). */
    FRAME_DELETE("frame:delete", "frame:deleted"),
    /** Changes an existing {@link Frame}'s Z-order layer; echoes {@code {id, layer}} (EN08, Frames). */
    FRAME_LAYER("frame:layer", "frame:layered"),
    /** Starts (or restarts) the board's shared facilitation timer. Inbound {@code timer:start}
     * carries {@code {duration}} (seconds); the server computes {@code endsAt} and broadcasts
     * {@code timer:started {endsAt, serverNow}} room-wide. EDITOR/OWNER only. Ephemeral (Redis,
     * {@link BoardTimerStore}) — never persisted. */
    TIMER_START("timer:start", "timer:started"),
    /** Stops the board's shared facilitation timer, broadcasting {@code timer:stopped}
     * room-wide. EDITOR/OWNER only. */
    TIMER_STOP("timer:stop", "timer:stopped"),
    /** Client-triggered destructive board reset: deletes every card and connector of the board,
     * then broadcasts {@code board:resetted} room-wide (OWNER only). Distinct from the legacy
     * server-emitted {@link #RESET} (bare enum name, DRAW-events reset driven by the REST
     * endpoint) — this one is a genuine inbound STOMP action the frontend
     * ({@code board.store.ts}) emits as {@code board:reset}. */
    BOARD_RESET("board:reset", "board:resetted");

    private static final Map<String, CanvasEventType> BY_WIRE_IN = Stream.of(values())
            .collect(Collectors.toMap(t -> t.wireIn.toLowerCase(Locale.ROOT), t -> t));

    private final String wireIn;
    private final String wireOut;

    CanvasEventType(final String wireIn) {
        this(wireIn, wireIn);
    }

    CanvasEventType(final String wireIn, final String wireOut) {
        this.wireIn = wireIn;
        this.wireOut = wireOut;
    }

    /**
     * Resolves an incoming message's raw {@code type} string to a constant, matching first
     * against the wire-in vocabulary ({@code card:create}, {@code board:join}...) then falling
     * back to the bare Java enum name (case-insensitive) for any caller that sends that
     * directly.
     *
     * @param raw the raw {@code type} field from an incoming {@code CanvasActionMessage}
     * @return the matching constant, or {@code null} if none match (caller drops the message)
     */
    public static CanvasEventType fromWire(final String raw) {
        if (raw == null) {
            return null;
        }
        CanvasEventType byWire = BY_WIRE_IN.get(raw.toLowerCase(Locale.ROOT));
        if (byWire != null) {
            return byWire;
        }
        try {
            return CanvasEventType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The wire name this constant is recognised under when a client sends it inbound.
     *
     * @return the incoming wire name
     */
    public String wireIn() {
        return wireIn;
    }

    /**
     * The wire name this constant is broadcast under in the {@code type} field sent to
     * clients — distinct from {@link #wireIn()} for the {@code CARD_*} mutations (past tense).
     *
     * @return the outgoing wire name
     */
    public String wireOut() {
        return wireOut;
    }
}
