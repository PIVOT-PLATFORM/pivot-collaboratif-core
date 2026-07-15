package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a target URL and extracts its OpenGraph preview metadata, with the exact resource
 * caps mandated by the parity spec (§7) and the SSRF hardening this US adds on top of it
 * (§6 "Correctif"): timeout 5000&nbsp;ms (enforced across the <em>whole</em> redirect chain, not
 * per-hop — a chain of slow redirecting hops must not multiply the wall-clock budget), body
 * read capped at 100000 bytes, at most 5 redirects (each hop re-validated against {@link
 * SsrfGuard} — a redirect to a private/loopback/link-local target is refused exactly like the
 * original URL would have been), and only {@code text/html}/{@code application/xhtml+xml}
 * responses are parsed.
 *
 * <p>Never lets an exception escape a caller expecting a clean absorb-and-move-on: every failure
 * mode (blocked SSRF target, malformed URL, DNS/connection failure, non-2xx status, wrong
 * content-type, timeout, too many redirects) is raised as {@link OpenGraphFetchException}, a
 * single type the caller ({@link OpenGraphEnrichmentListener}) catches generically.
 */
@Component
class OpenGraphFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(OpenGraphFetcher.class);

    /** Hard cap on the number of redirects followed (parity spec §7). */
    static final int MAX_REDIRECTS = 5;

    /** Hard cap on HTML bytes read from the response body (parity spec §7). */
    static final int MAX_BODY_BYTES = 100_000;

    /** Overall fetch timeout, across every hop of a redirect chain (parity spec §7). */
    static final long TIMEOUT_MILLIS = 5_000;

    /** Hard cap on the {@code description} field's length (parity spec §7). */
    static final int MAX_DESCRIPTION_LENGTH = 300;

    // Tag boundary is quote-aware: a quoted attribute value (single or double) is matched as one
    // atomic run so an embedded '>' inside it (e.g. a smuggled "<script>" inside content="...")
    // never prematurely closes the tag match — see OpenGraphFetcherTest for the adversarial case.
    private static final Pattern META_TAG = Pattern.compile(
            "<meta\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_TAG =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTR =
            Pattern.compile("([a-zA-Z][a-zA-Z0-9:_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final Pattern TAG_STRIP = Pattern.compile("<[^>]*>");

    private final SsrfGuard ssrfGuard;
    private final HttpClient httpClient;

    /**
     * Creates the fetcher.
     *
     * @param ssrfGuard validates every URL (original and each redirect hop) before connecting
     */
    OpenGraphFetcher(final SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(TIMEOUT_MILLIS))
                // Redirects are followed manually below so every hop can be re-validated
                // against SsrfGuard — HttpClient.Redirect.NORMAL would follow blindly.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Fetches {@code rawUrl} and returns its sanitised OpenGraph metadata.
     *
     * @param rawUrl the candidate URL (already type/regex-eligible per {@link CardUrlExtractor})
     * @return the parsed, sanitised metadata
     * @throws OpenGraphFetchException on any failure — SSRF-blocked target, malformed URL,
     *                                 timeout, DNS/connection failure, non-2xx status, wrong
     *                                 content-type, or too many redirects
     */
    Optional<OpenGraphMeta> fetch(final String rawUrl) {
        Instant deadline = Instant.now().plusMillis(TIMEOUT_MILLIS);
        String currentUrl = rawUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = parseUri(currentUrl);
            ssrfGuard.validate(uri);
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new OpenGraphFetchException("timeout budget exhausted before hop " + hop);
            }
            HttpResponse<InputStream> response = send(uri, remaining);
            int status = response.statusCode();
            if (isRedirect(status)) {
                if (hop == MAX_REDIRECTS) {
                    discard(response);
                    throw new OpenGraphFetchException("too many redirects (> " + MAX_REDIRECTS + ")");
                }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new OpenGraphFetchException("redirect with no Location header"));
                discard(response);
                currentUrl = uri.resolve(location).toString();
                continue;
            }
            if (status < 200 || status >= 300) {
                discard(response);
                throw new OpenGraphFetchException("non-2xx status: " + status);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!isHtmlContentType(contentType)) {
                discard(response);
                throw new OpenGraphFetchException("unsupported content-type: " + contentType);
            }
            String html = readBounded(response.body(), MAX_BODY_BYTES);
            return Optional.of(parseAndSanitize(html));
        }
        throw new OpenGraphFetchException("too many redirects (> " + MAX_REDIRECTS + ")");
    }

    private HttpResponse<InputStream> send(final URI uri, final Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "PivotWhiteboardLinkPreview/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new OpenGraphFetchException("connection failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenGraphFetchException("interrupted while fetching", e);
        }
    }

    private URI parseUri(final String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new OpenGraphFetchException("malformed URL: " + url, e);
        }
    }

    private boolean isRedirect(final int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private boolean isHtmlContentType(final String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("text/html") || lower.contains("application/xhtml+xml");
    }

    private void discard(final HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException e) {
            LOG.debug("Failed to close discarded OpenGraph response body: {}", e.getMessage());
        }
    }

    /**
     * Reads at most {@code maxBytes} from {@code in}, then stops — never buffers the full
     * response in memory regardless of the target's actual (or claimed) size, and always closes
     * the stream (package-private, unit-tested directly with a synthetic oversized stream).
     *
     * @param in       the response body stream
     * @param maxBytes the hard byte cap
     * @return the decoded (UTF-8) string, truncated at {@code maxBytes} raw bytes
     */
    static String readBounded(final InputStream in, final int maxBytes) {
        try (in) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
            int total = 0;
            int read;
            while (total < maxBytes
                    && (read = in.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OpenGraphFetchException("failed reading response body: " + e.getMessage(), e);
        }
    }

    /**
     * Parses the OpenGraph {@code <meta>} tags (falling back to {@code <title>}/{@code
     * meta[name=description]} for title/description) out of a (possibly truncated) HTML
     * fragment, and sanitises every extracted field (package-private, unit-tested directly with
     * canned HTML fragments — no network involved).
     *
     * @param html the HTML fragment (already capped to {@link #MAX_BODY_BYTES})
     * @return the sanitised metadata (all fields nullable)
     */
    static OpenGraphMeta parseAndSanitize(final String html) {
        String ogTitle = null;
        String ogDescription = null;
        String ogImage = null;
        String ogSiteName = null;
        String metaDescriptionFallback = null;

        Matcher tagMatcher = META_TAG.matcher(html);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            String key = firstAttr(tag, "property");
            if (key == null) {
                key = firstAttr(tag, "name");
            }
            if (key == null) {
                continue;
            }
            String content = firstAttr(tag, "content");
            if (content == null) {
                continue;
            }
            switch (key.toLowerCase(Locale.ROOT)) {
                case "og:title" -> ogTitle = ogTitle == null ? content : ogTitle;
                case "og:description" -> ogDescription = ogDescription == null ? content : ogDescription;
                case "og:image", "og:image:url", "og:image:secure_url" ->
                        ogImage = ogImage == null ? content : ogImage;
                case "og:site_name" -> ogSiteName = ogSiteName == null ? content : ogSiteName;
                case "description" -> metaDescriptionFallback =
                        metaDescriptionFallback == null ? content : metaDescriptionFallback;
                default -> { /* not an OpenGraph field we track */ }
            }
        }
        if (ogTitle == null) {
            Matcher titleMatcher = TITLE_TAG.matcher(html);
            if (titleMatcher.find()) {
                ogTitle = titleMatcher.group(1);
            }
        }
        if (ogDescription == null) {
            ogDescription = metaDescriptionFallback;
        }

        String title = sanitizeText(ogTitle);
        String description = sanitizeText(ogDescription);
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            description = description.substring(0, MAX_DESCRIPTION_LENGTH);
        }
        String image = sanitizeImageUrl(ogImage);
        String siteName = sanitizeText(ogSiteName);
        return new OpenGraphMeta(title, description, image, siteName);
    }

    /**
     * Extracts the first value of the named attribute from a single {@code <tag ...>} fragment,
     * tolerating either single or double quoting and any attribute order.
     *
     * @param tag       the raw tag markup (e.g. {@code <meta property="og:title" content="x">})
     * @param attrName  the attribute name to look up (case-insensitive)
     * @return the attribute's raw (still HTML-encoded) value, or {@code null} if absent
     */
    private static String firstAttr(final String tag, final String attrName) {
        Matcher matcher = ATTR.matcher(tag);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(attrName)) {
                return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            }
        }
        return null;
    }

    /**
     * Sanitises a raw, possibly HTML-entity-encoded text field extracted from an untrusted page:
     * decodes common HTML entities (so legitimate text like {@code "Bed &amp; Breakfast"} reads
     * naturally), then strips any literal {@code <...>} tag-like sequence a malicious page might
     * have smuggled into a {@code content="..."} attribute (raw {@code <} is not required to be
     * escaped inside an HTML attribute value by non-conforming producers). The result is a plain
     * string with no markup — safe to store, and safe to render via Angular interpolation
     * ({@code {{ }}}), which HTML-escapes it again at render time (defence in depth, not
     * redundant: this step defeats a raw-tag-injection page even if some future call site ever
     * renders it outside Angular's escaping, e.g. a non-Angular consumer of the same API).
     *
     * @param raw the raw attribute/tag text value, or {@code null}
     * @return the sanitised plain text, or {@code null} if absent/blank after sanitisation
     */
    private static String sanitizeText(final String raw) {
        if (raw == null) {
            return null;
        }
        String decoded = decodeHtmlEntities(raw);
        String stripped = TAG_STRIP.matcher(decoded).replaceAll("");
        String trimmed = stripped.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Validates that a raw {@code og:image} value is a well-formed absolute {@code http}/
     * {@code https} URL (parity spec Security AC) — anything else (relative path, {@code
     * javascript:}, {@code data:}, malformed) is dropped rather than stored, since the frontend
     * renders it directly as an {@code <img src>}.
     *
     * @param raw the raw {@code og:image} attribute value, or {@code null}
     * @return the validated absolute URL string, or {@code null} if invalid/absent
     */
    private static String sanitizeImageUrl(final String raw) {
        String candidate = sanitizeText(raw);
        if (candidate == null) {
            return null;
        }
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Decodes the small set of HTML entities realistically found in OpenGraph tag values: the
     * five predefined XML entities, {@code &nbsp;}, and numeric character references ({@code
     * &#39;}, {@code &#x27;}...). Deliberately not a full HTML5 named-entity table — OpenGraph
     * producers overwhelmingly stick to this set for attribute values.
     *
     * @param text the raw text possibly containing HTML entities
     * @return the decoded text
     */
    private static String decodeHtmlEntities(final String text) {
        String result = text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
        Matcher matcher = NUMERIC_ENTITY.matcher(result);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String code = matcher.group(1);
            try {
                int codePoint = code.startsWith("x") || code.startsWith("X")
                        ? Integer.parseInt(code.substring(1), 16)
                        : Integer.parseInt(code);
                matcher.appendReplacement(builder, Matcher.quoteReplacement(
                        Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : ""));
            } catch (NumberFormatException e) {
                matcher.appendReplacement(builder, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(builder);
        return builder.toString();
    }
}
