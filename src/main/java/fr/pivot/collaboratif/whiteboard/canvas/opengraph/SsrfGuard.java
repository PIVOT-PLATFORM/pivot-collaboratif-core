package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import java.net.URI;

/**
 * Validates that a URL is safe to fetch server-side before {@link OpenGraphFetcher} connects to
 * it (US08.6.5 — SSRF hardening; this is a PIVOT-added correctif not present in the reference
 * POC, see this US's backlog "Notes d'implémentation" §6).
 *
 * <p>An implementation must reject:
 * <ul>
 *   <li>any scheme other than {@code http}/{@code https} ({@code file://}, {@code gopher://}...)</li>
 *   <li>any hostname that resolves (any of its resolved addresses, not just the first) to a
 *       loopback, link-local, site-local/RFC 1918, unique-local IPv6 ({@code fd00::/8}), or
 *       otherwise reserved address — including the cloud metadata endpoint
 *       {@code 169.254.169.254}, itself already covered by the link-local range</li>
 * </ul>
 *
 * <p>Exposed as an interface (rather than a concrete final class) purely so integration tests
 * that need {@link OpenGraphFetcher} to reach a local test HTTP server can substitute a
 * permissive test double for the loopback check via a mock bean, without weakening the real
 * {@link DefaultSsrfGuard} used in production — see {@code WhiteboardLinkFetchIT} for that
 * substitution, and {@code WhiteboardLinkSsrfIT} for full end-to-end coverage of the
 * <strong>real</strong> guard rejecting private/loopback/link-local targets.
 */
public interface SsrfGuard {

    /**
     * Validates {@code uri} against the scheme allowlist and the private/loopback/link-local/
     * cloud-metadata blocklist.
     *
     * @param uri the candidate URI (the original URL, or a redirect target — the caller must
     *            call this again for every redirect hop, never trusting a single check to cover
     *            the whole chain)
     * @throws OpenGraphFetchException if the scheme is not {@code http}/{@code https}, the host
     *                                 is missing/unresolvable, or any resolved address is
     *                                 private/loopback/link-local/reserved
     */
    void validate(URI uri);
}
