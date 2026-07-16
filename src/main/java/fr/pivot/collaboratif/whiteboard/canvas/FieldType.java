package fr.pivot.collaboratif.whiteboard.canvas;

/**
 * Typed discriminant for a custom board field ({@link BoardField}, US08.10.1).
 *
 * <p>Exactly four kinds of column a board owner/editor can define on a board:
 * <ul>
 *   <li>{@link #TEXT} — free-text value;</li>
 *   <li>{@link #NUMBER} — numeric value;</li>
 *   <li>{@link #DATE} — date value;</li>
 *   <li>{@link #SELECT} — a value chosen from a finite {@code options} list.</li>
 * </ul>
 *
 * <p>The type is fixed at creation and never changed afterwards (acceptance criterion) — only a
 * field's {@code name}/{@code emoji}/{@code options}/{@code order} are mutable. The wire vocabulary
 * is the bare uppercase enum name, resolved defensively by {@link #fromWire}: an invalid value
 * yields {@code null} so the {@code boardfield:create} handler can validate before persisting and
 * silently drop an invalid request (§6.6 fix) rather than throwing.
 */
public enum FieldType {
    /** Free-text value. */
    TEXT,
    /** Numeric value. */
    NUMBER,
    /** Date value. */
    DATE,
    /** Value chosen from a finite {@code options} list. */
    SELECT;

    /**
     * Resolves a raw wire {@code type} string to a constant, case-sensitively (the wire vocabulary
     * is the bare uppercase enum name). Returns {@code null} — never throws — for a {@code null},
     * blank, or unrecognised value, so a caller can validate before persisting and drop an invalid
     * {@code boardfield:create} silently (§6.6 fix: validate before persist, never throw).
     *
     * @param raw the raw {@code type} value from the incoming action's {@code data} map
     * @return the matching constant, or {@code null} if {@code raw} is not one of the four names
     */
    public static FieldType fromWire(final String raw) {
        if (raw == null) {
            return null;
        }
        for (FieldType type : values()) {
            if (type.name().equals(raw)) {
                return type;
            }
        }
        return null;
    }
}
