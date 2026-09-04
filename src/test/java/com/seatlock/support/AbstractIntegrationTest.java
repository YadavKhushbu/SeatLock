package com.seatlock.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need the real thing.
 *
 * <p>These run against actual Postgres and actual Redis, never H2 or an embedded
 * substitute. That is not thoroughness for its own sake: the guarantees under
 * test are {@code SELECT ... FOR UPDATE} semantics and partial unique indexes,
 * and an in-memory database either implements those differently or not at all. A
 * test on H2 would pass while the production database behaved differently, which
 * is worse than having no test.
 *
 * <p>Containers are static, so one Postgres and one Redis are shared by every
 * test class in the run rather than restarted per class.
 *
 * <p>Subclasses must carry {@link IntegrationTest} themselves; this class only
 * supplies the containers.
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("seatlock")
                    .withUsername("seatlock")
                    .withPassword("seatlock")
                    .withReuse(true);

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    private static boolean started;

    /**
     * Starts the containers on first use, and never from a static initialiser.
     *
     * <p>JUnit has to load this class before it can evaluate the {@code @EnabledIf}
     * condition above. A static block that calls {@code start()} therefore runs
     * <em>before</em> the skip is considered, and on a machine without Docker the
     * whole class dies with ExceptionInInitializerError instead of skipping.
     * Deferring to {@code @DynamicPropertySource} puts startup after the
     * condition, where it belongs.
     */
    private static synchronized void ensureStarted() {
        if (!started) {
            // Started once for the whole JVM and left running. Testcontainers'
            // Ryuk sidecar reaps them when the run ends, so nothing to stop.
            POSTGRES.start();
            REDIS.start();
            started = true;
        }
    }

    @DynamicPropertySource
    static void wireContainers(DynamicPropertyRegistry registry) {
        ensureStarted();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
