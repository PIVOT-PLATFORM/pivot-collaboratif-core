package fr.pivot.collaboratif.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the EN07.2 Docker secrets mechanism: {@code application-prod.yml}'s
 * {@code spring.config.import: optional:configtree:${SECRET_FILE_PATH:/run/secrets}/} and the
 * {@code secret.datasource-password} fallback placeholder declared in {@code application.yml}.
 *
 * <p>No custom Java code backs this mechanism (pure Spring Boot config tree + relaxed
 * placeholder resolution, same pattern as {@code pivot-core}'s own EN07.2) — these tests boot a
 * minimal, auto-configuration-free Spring context against the real {@code classpath:
 * application.yml} / {@code classpath:application-prod.yml} so a regression in the YAML wiring
 * itself (wrong key, wrong nesting, wrong profile, wrong precedence) fails the build rather than
 * only being caught manually in a deployed environment — mirrors {@code pivot-core}'s
 * {@code fr.pivot.config.SecretManagementConfigTest}.
 *
 * <p>Traçabilité EN07.2 — unblocks the {@code postgres_password} Docker secret wired in
 * {@code pivot-core#217}'s {@code docker-compose.prod.yml} (target {@code
 * secret.datasource-password}).
 */
class SecretManagementConfigTest {

    @TempDir
    Path secretsDir;

    private static ConfigurableApplicationContext boot(final String... properties) {
        return new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    @Test
    void ac1_resolvesDockerSecretFromConfigTree_whenProdProfileActive() throws IOException {
        Files.writeString(secretsDir.resolve("secret.datasource-password"), "s3cr3t-from-docker");

        try (ConfigurableApplicationContext ctx = boot(
                "spring.profiles.active=prod",
                "SECRET_FILE_PATH=" + secretsDir)) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.password"))
                    .isEqualTo("s3cr3t-from-docker");
        }
    }

    @Test
    void ac1_envVarTakesPrecedenceOverDockerSecret_whenBothPresent() throws IOException {
        Files.writeString(secretsDir.resolve("secret.datasource-password"), "from-docker-secret");

        try (ConfigurableApplicationContext ctx = boot(
                "spring.profiles.active=prod",
                "SECRET_FILE_PATH=" + secretsDir,
                "SPRING_DATASOURCE_PASSWORD=from-env-var")) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.password"))
                    .isEqualTo("from-env-var");
        }
    }

    @Test
    void ac1_fallsBackToLocalDefault_whenNeitherEnvVarNorSecretFilePresent() {
        try (ConfigurableApplicationContext ctx = boot(
                "spring.profiles.active=prod",
                "SECRET_FILE_PATH=" + secretsDir)) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("pivot");
        }
    }

    @Test
    void ac1_doesNotFailStartup_whenSecretDirectoryDoesNotExistAtAll() {
        // Default SECRET_FILE_PATH (/run/secrets) is virtually guaranteed absent outside a
        // container — "optional:" must prevent a ConfigDataLocationNotFoundException here.
        try (ConfigurableApplicationContext ctx = boot("spring.profiles.active=prod")) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("pivot");
        }
    }

    @Test
    void ac2_neverImportsConfigTree_whenProdProfileInactive() throws IOException {
        // Zero-secret-in-clear-text is only meaningful together with: the mechanism must not
        // silently activate outside the prod profile. secret.* stays entirely unresolved.
        Files.writeString(secretsDir.resolve("secret.datasource-password"), "should-be-ignored");

        try (ConfigurableApplicationContext ctx = boot("SECRET_FILE_PATH=" + secretsDir)) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("pivot");
            assertThat(ctx.getEnvironment().getProperty("secret.datasource-password")).isNull();
        }
    }

    /** Deliberately auto-configuration-free: this test is about property resolution only. */
    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }
}
