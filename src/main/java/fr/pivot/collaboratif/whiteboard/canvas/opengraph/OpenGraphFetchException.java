package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

/**
 * Unchecked exception for any failure while fetching or validating an OpenGraph target
 * (US08.6.5): blocked SSRF target, malformed URL, DNS/connection failure, non-2xx status,
 * unsupported content-type, timeout, or too many redirects.
 *
 * <p>Always caught and absorbed by {@link OpenGraphEnrichmentListener} — this type exists only
 * to give the fetch pipeline a single, precise exception to throw internally; it must never
 * propagate out of the listener (parity spec: a failed fetch is silent, the card simply keeps
 * {@code meta = null}).
 */
final class OpenGraphFetchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a diagnostic message (logged at debug level by the caller,
     * never shown to a client).
     *
     * @param message a short diagnostic message
     */
    OpenGraphFetchException(final String message) {
        super(message);
    }

    /**
     * Creates the exception wrapping a lower-level cause (e.g. {@code IOException},
     * {@code UnknownHostException}).
     *
     * @param message a short diagnostic message
     * @param cause   the underlying cause
     */
    OpenGraphFetchException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
