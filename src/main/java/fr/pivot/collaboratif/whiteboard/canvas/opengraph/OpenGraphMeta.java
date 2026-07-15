package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

/**
 * Sanitised OpenGraph preview metadata for a {@code LINK}/{@code TEXT}/{@code LABEL} card
 * (US08.6.5) — every field is already HTML-tag-stripped/entity-decoded plain text (or, for
 * {@link #image()}, a validated {@code http}/{@code https} absolute URL) by the time this record
 * is built; see {@link OpenGraphFetcher#sanitizeText} and {@link OpenGraphFetcher#sanitizeImageUrl}.
 *
 * @param title       the page's {@code og:title} (or {@code <title>} fallback), or {@code null}
 * @param description the page's {@code og:description} (or {@code meta[name=description]}
 *                     fallback), truncated to 300 characters (parity spec §7), or {@code null}
 * @param image       the page's {@code og:image}, validated as an absolute {@code http}/
 *                    {@code https} URL, or {@code null}
 * @param siteName    the page's {@code og:site_name}, or {@code null}
 */
record OpenGraphMeta(String title, String description, String image, String siteName) {
}
