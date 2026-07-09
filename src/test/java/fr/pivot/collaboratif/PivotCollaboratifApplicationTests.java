package fr.pivot.collaboratif;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifie que le contexte Spring demarre correctement avec les services infra reels. */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PivotCollaboratifApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived connection properties and seeds the {@code public} schema
     * (owned by {@code pivot-core}, not managed by this repo's own Flyway) before the Spring
     * context and its Flyway run start — {@code collaboratif.board}/{@code canvas_event}/etc.
     * now carry FK constraints into {@code public.tenants}/{@code public.users} (EN08.3).
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        PlatformAuthTestSupport.createPublicSchema(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void contextLoads() {
    }
}
