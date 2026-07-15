package fr.pivot.collaboratif.whiteboard.canvas.opengraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DefaultSsrfGuard} — the SSRF hardening this US adds on top of the
 * reference POC (parity spec §6 "Correctif"). Uses only IP-literal targets so no real DNS lookup
 * happens (fast, deterministic, no network in a unit test).
 */
class DefaultSsrfGuardTest {

    private final DefaultSsrfGuard guard = new DefaultSsrfGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://127.0.0.1:8080/path",
            "https://127.0.0.1/",
            "http://[::1]/",
    })
    void rejects_loopback_targets(final String url) {
        assertThatThrownBy(() -> guard.validate(URI.create(url)))
                .isInstanceOf(OpenGraphFetchException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.169.254/",
            "http://169.254.1.1/",
    })
    void rejects_link_local_and_cloud_metadata_targets(final String url) {
        assertThatThrownBy(() -> guard.validate(URI.create(url)))
                .isInstanceOf(OpenGraphFetchException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.5/",
            "http://172.16.0.5/",
            "http://172.31.255.255/",
            "http://192.168.1.1/",
    })
    void rejects_rfc1918_private_targets(final String url) {
        assertThatThrownBy(() -> guard.validate(URI.create(url)))
                .isInstanceOf(OpenGraphFetchException.class);
    }

    @Test
    void rejects_ipv6_unique_local_fd00_slash_8() {
        assertThatThrownBy(() -> guard.validate(URI.create("http://[fd12:3456:789a::1]/")))
                .isInstanceOf(OpenGraphFetchException.class);
    }

    @Test
    void rejects_ipv4_mapped_ipv6_loopback_bypass_attempt() {
        assertThatThrownBy(() -> guard.validate(URI.create("http://[::ffff:127.0.0.1]/")))
                .isInstanceOf(OpenGraphFetchException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "gopher://example.com/", "ftp://example.com/"})
    void rejects_non_http_schemes(final String url) {
        assertThatThrownBy(() -> guard.validate(URI.create(url)))
                .isInstanceOf(OpenGraphFetchException.class);
    }

    @Test
    void rejects_uri_with_no_host() {
        assertThatThrownBy(() -> guard.validate(URI.create("http:///no-host")))
                .isInstanceOf(OpenGraphFetchException.class);
    }

    @Test
    void rejects_unresolvable_host() {
        assertThatThrownBy(() -> guard.validate(URI.create("http://this-host-does-not-exist.invalid/")))
                .isInstanceOf(OpenGraphFetchException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://8.8.8.8/", "https://8.8.8.8/", "http://1.1.1.1/"})
    void allows_public_ip_literal_targets(final String url) {
        assertThatCode(() -> guard.validate(URI.create(url))).doesNotThrowAnyException();
    }
}
