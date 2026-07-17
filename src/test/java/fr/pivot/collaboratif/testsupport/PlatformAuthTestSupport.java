package fr.pivot.collaboratif.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Test-only support for seeding the {@code public} schema (owned by {@code pivot-core}, not by
 * this repo's own Flyway — which manages {@code collaboratif} only) against a per-test-class
 * Testcontainers PostgreSQL instance, and issuing real bearer tokens for it (EN08.3).
 *
 * <p>Every {@code *IT} class under {@code whiteboard/} declares its own {@code @Container
 * PostgreSQLContainer<?>} (no shared base class in this repo — see CLAUDE.md test conventions).
 * {@link #createPublicSchema(String, String, String)} must be called once per container, from
 * the same {@code @DynamicPropertySource} static method that already registers the datasource
 * properties — at that point the container is up but Spring's context (and therefore Flyway,
 * which now has FK references from {@code collaboratif.*} into {@code public.tenants}/{@code
 * public.users}) has not started yet, so the {@code public} tables must already exist.
 *
 * <p>Deliberately raw JDBC, not {@code JdbcTemplate}/Spring Data — this is test-only setup code
 * for a schema this repo's own persistence layer never manages, not production access (the
 * "no JdbcTemplate in production code" rule in CLAUDE.md does not apply here).
 */
public final class PlatformAuthTestSupport {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private PlatformAuthTestSupport() {
    }

    /**
     * Creates the minimal {@code public.tenants}/{@code public.users}/{@code public.access_tokens}
     * tables this repo's read-only auth entities map to. Idempotent ({@code CREATE TABLE IF NOT
     * EXISTS}) — safe to call once per container right after it starts.
     *
     * @param jdbcUrl  the Testcontainers-issued JDBC URL
     * @param username the database username
     * @param password the database password
     * @throws SQLException if the DDL fails
     */
    public static void createPublicSchema(final String jdbcUrl, final String username, final String password)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public.tenants (
                        id BIGSERIAL PRIMARY KEY,
                        slug VARCHAR(100) NOT NULL UNIQUE,
                        name VARCHAR(255) NOT NULL,
                        is_active BOOLEAN NOT NULL DEFAULT true,
                        tenant_invalidation_timestamp TIMESTAMPTZ,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public.users (
                        id BIGSERIAL PRIMARY KEY,
                        tenant_id BIGINT NOT NULL REFERENCES public.tenants(id),
                        email VARCHAR(320) NOT NULL,
                        role VARCHAR(50) NOT NULL DEFAULT 'ROLE_USER',
                        is_active BOOLEAN NOT NULL DEFAULT true,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public.access_tokens (
                        id BIGSERIAL PRIMARY KEY,
                        user_id BIGINT NOT NULL REFERENCES public.users(id),
                        token_hash VARCHAR(64) NOT NULL UNIQUE,
                        status VARCHAR(10) NOT NULL DEFAULT 'active',
                        expires_at TIMESTAMPTZ NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
        }
    }

    /**
     * Inserts a tenant row.
     *
     * @param jdbcUrl                the JDBC URL
     * @param username               the database username
     * @param password               the database password
     * @param invalidationTimestamp  the tenant's {@code tenant_invalidation_timestamp}, or
     *                               {@code null} if the tenant was never deactivated
     * @return the generated {@code public.tenants.id}
     * @throws SQLException if the insert fails
     */
    public static long seedTenant(
            final String jdbcUrl, final String username, final String password,
            final Instant invalidationTimestamp) throws SQLException {
        final String slug = "t-" + UUID.randomUUID();
        final String sql = "INSERT INTO public.tenants (slug, name, tenant_invalidation_timestamp) "
                + "VALUES (?, ?, ?) RETURNING id";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            ps.setString(2, slug);
            if (invalidationTimestamp != null) {
                ps.setObject(3, OffsetDateTime.ofInstant(invalidationTimestamp, ZoneOffset.UTC));
            } else {
                ps.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Inserts a user row.
     *
     * @param jdbcUrl  the JDBC URL
     * @param username the database username
     * @param password the database password
     * @param tenantId the owning tenant's id
     * @param active   the {@code is_active} value
     * @return the generated {@code public.users.id}
     * @throws SQLException if the insert fails
     */
    public static long seedUser(
            final String jdbcUrl, final String username, final String password,
            final long tenantId, final boolean active) throws SQLException {
        final String email = UUID.randomUUID() + "@pivot.invalid";
        final String sql = "INSERT INTO public.users (tenant_id, email, role, is_active) "
                + "VALUES (?, ?, 'ROLE_USER', ?) RETURNING id";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, email);
            ps.setBoolean(3, active);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Inserts a user row with an explicit e-mail address (US08.2.5 invitation-by-email tests).
     *
     * @param jdbcUrl  the JDBC URL
     * @param username the database username
     * @param password the database password
     * @param tenantId the owning tenant's id
     * @param email    the user's e-mail address
     * @param active   the {@code is_active} value
     * @return the generated {@code public.users.id}
     * @throws SQLException if the insert fails
     */
    public static long seedUserWithEmail(
            final String jdbcUrl, final String username, final String password,
            final long tenantId, final String email, final boolean active) throws SQLException {
        final String sql = "INSERT INTO public.users (tenant_id, email, role, is_active) "
                + "VALUES (?, ?, 'ROLE_USER', ?) RETURNING id";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, email);
            ps.setBoolean(3, active);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Issues a bearer token row for {@code userId} and returns the raw token to send in an
     * {@code Authorization: Bearer <token>} header.
     *
     * @param jdbcUrl   the JDBC URL
     * @param username  the database username
     * @param password  the database password
     * @param userId    the owning user's id
     * @param status    the lowercase lifecycle status ({@code active}/{@code expired}/{@code revoked})
     * @param expiresAt the token's expiry timestamp
     * @return the raw (unhashed) token
     * @throws SQLException if the insert fails
     */
    public static String issueToken(
            final String jdbcUrl, final String username, final String password,
            final long userId, final String status, final Instant expiresAt) throws SQLException {
        final byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        final String rawToken = HEX.formatHex(tokenBytes);
        final String sql = "INSERT INTO public.access_tokens (user_id, token_hash, status, expires_at) "
                + "VALUES (?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, sha256(rawToken));
            ps.setString(3, status);
            ps.setObject(4, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            ps.executeUpdate();
        }
        return rawToken;
    }

    /**
     * Convenience one-shot: seeds an active tenant, an active user in it, and a valid
     * (non-expired, {@code active}) token for that user.
     *
     * @param jdbcUrl  the JDBC URL
     * @param username the database username
     * @param password the database password
     * @return the seeded fixture (tenant id, user id, raw bearer token)
     * @throws SQLException if any insert fails
     */
    public static AuthFixture seedActiveUserWithToken(
            final String jdbcUrl, final String username, final String password) throws SQLException {
        final long tenantId = seedTenant(jdbcUrl, username, password, null);
        final long userId = seedUser(jdbcUrl, username, password, tenantId, true);
        final String token = issueToken(
                jdbcUrl, username, password, userId, "active", Instant.now().plusSeconds(3600));
        return new AuthFixture(tenantId, userId, token);
    }

    /**
     * Returns the lowercase hex-encoded SHA-256 digest of {@code rawToken} — mirrors {@link
     * fr.pivot.collaboratif.auth.TokenValidationService}'s own hashing so seeded rows match what
     * the service under test will look up.
     *
     * @param rawToken the plaintext token to hash
     * @return 64-character hex string
     */
    private static String sha256(final String rawToken) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * A seeded tenant/user/token triple ready to use as an {@code Authorization: Bearer} header.
     *
     * @param tenantId the seeded tenant's {@code public.tenants.id}
     * @param userId   the seeded user's {@code public.users.id}
     * @param rawToken the raw bearer token — use as {@code "Bearer " + rawToken}
     */
    public record AuthFixture(long tenantId, long userId, String rawToken) {

        /**
         * @return the value to send as the {@code Authorization} header
         */
        public String authorizationHeader() {
            return "Bearer " + rawToken;
        }
    }
}
