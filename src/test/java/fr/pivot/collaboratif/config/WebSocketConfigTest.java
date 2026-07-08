package fr.pivot.collaboratif.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the EN07.3 domain-isolation guarantee in {@link WebSocketConfig}.
 *
 * <p>Security AC: only destinations under the collaboratif domain's own prefix are ever
 * relayed to the shared ActiveMQ broker by this module — Spring's
 * {@code AbstractBrokerMessageHandler} silently refuses to relay anything outside a
 * registration's configured prefixes, so the prefix constant itself is the enforcement
 * boundary. This test pins that constant so a future edit cannot silently widen or rename it
 * without a visible test failure.
 */
class WebSocketConfigTest {

    /**
     * Given the EN07.3 STOMP broker relay configuration,
     * when the domain relay prefix is inspected,
     * then it is exactly {@code /topic/collaboratif.} — dot-terminated (never slash, never a
     * bare {@code /topic/collaboratif} that could accidentally prefix-match a future domain
     * such as {@code collaboratifx}), and scoped to this module's own domain only (never
     * {@code /topic/pilotage.} or {@code /topic/agilite.}).
     */
    @Test
    void domainRelayPrefixIsExactlyTheCollaboratifDomainWithTrailingDot() {
        assertThat(WebSocketConfig.DOMAIN_RELAY_PREFIX).isEqualTo("/topic/collaboratif.");
        assertThat(WebSocketConfig.DOMAIN_RELAY_PREFIX)
                .as("must not collide with a future similarly-named domain")
                .doesNotStartWith("/topic/collaboratifx")
                .isNotEqualTo("/topic/collaboratif");
        assertThat(WebSocketConfig.DOMAIN_RELAY_PREFIX)
                .as("must not be another module's domain prefix")
                .isNotEqualTo("/topic/pilotage.")
                .isNotEqualTo("/topic/agilite.");
    }
}
