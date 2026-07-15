package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Production {@link SsrfGuard} — resolves the target hostname and rejects it if either the
 * scheme is not {@code http}/{@code https}, or any of its resolved addresses falls in a
 * loopback/link-local/site-local (RFC 1918)/unique-local-IPv6 ({@code fd00::/8}) range, which
 * also covers the cloud metadata endpoint {@code 169.254.169.254} (link-local).
 *
 * <p><strong>Known residual risk (documented, not silently glossed over — flagged for Gate 4
 * review, see this PR's description):</strong> this check resolves DNS once, immediately before
 * connecting. It does not pin the connection to the validated address — a DNS answer that
 * changes between this check and the actual TCP connect (DNS rebinding) could in theory bypass
 * it. Pinning the connection to a specific resolved {@link InetAddress} while still presenting
 * the original hostname for TLS SNI/certificate validation is not achievable with {@code
 * java.net.http.HttpClient}'s public API without a custom {@code SSLContext}/socket factory,
 * which was judged out of scope for this Socle-sized US; the redirect-revalidation and
 * scheme/DNS/blocklist checks below still stop the overwhelming majority of realistic SSRF
 * payloads (direct private/loopback/metadata targets, and redirect-based pivoting to one).
 */
@Component
class DefaultSsrfGuard implements SsrfGuard {

    @Override
    public void validate(final URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new OpenGraphFetchException("scheme not allowed: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new OpenGraphFetchException("missing host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new OpenGraphFetchException("DNS resolution failed for host: " + host, e);
        }
        if (addresses.length == 0) {
            throw new OpenGraphFetchException("no address resolved for host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new OpenGraphFetchException(
                        "blocked target address " + address.getHostAddress() + " for host " + host);
            }
        }
    }

    /**
     * Returns whether the given resolved address falls in a blocked range: loopback, link-local
     * (covers {@code 169.254.0.0/16} and the cloud metadata endpoint), site-local (RFC 1918),
     * multicast, the unspecified address, IPv6 unique-local ({@code fd00::/8}), or an
     * IPv4-mapped IPv6 address whose embedded IPv4 address is itself blocked.
     *
     * @param address the resolved address to check
     * @return {@code true} if this address must never be connected to
     */
    private boolean isBlocked(final InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            // IPv6 unique local address range fd00::/8 — Inet6Address#isSiteLocalAddress only
            // recognises the deprecated fec0::/10 range, not this one, so check explicitly.
            if ((bytes[0] & 0xFF) == 0xFD) {
                return true;
            }
            if (isIpv4Mapped(bytes)) {
                return isBlockedEmbeddedIpv4(bytes);
            }
        }
        return false;
    }

    /**
     * Returns whether {@code bytes} is an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) —
     * the first 10 bytes zero, followed by {@code 0xFF 0xFF}.
     *
     * @param bytes the 16-byte IPv6 address
     * @return {@code true} if this is an IPv4-mapped address
     */
    private boolean isIpv4Mapped(final byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    /**
     * Re-checks the IPv4 address embedded in an IPv4-mapped IPv6 address against the same
     * blocklist, closing an otherwise trivial bypass ({@code ::ffff:127.0.0.1}).
     *
     * @param mappedBytes the 16-byte IPv4-mapped IPv6 address
     * @return {@code true} if the embedded IPv4 address is itself blocked (fails closed —
     *     {@code true} — if it cannot even be constructed)
     */
    private boolean isBlockedEmbeddedIpv4(final byte[] mappedBytes) {
        byte[] embedded = {mappedBytes[12], mappedBytes[13], mappedBytes[14], mappedBytes[15]};
        try {
            return isBlocked(InetAddress.getByAddress(embedded));
        } catch (UnknownHostException e) {
            return true;
        }
    }
}
