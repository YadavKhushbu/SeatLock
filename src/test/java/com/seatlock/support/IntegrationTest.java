package com.seatlock.support;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.*;

/**
 * Marks a test that needs the full stack: Spring context, Postgres and Redis.
 *
 * <p>Applied directly to each concrete test class rather than left to be
 * inherited from {@link AbstractIntegrationTest}. JUnit resolves conditions
 * against the class it is about to run, and relying on the annotation being
 * picked up through a superclass is the kind of thing that silently stops
 * working: the failure mode is not a skipped test but a whole class erroring out
 * on a machine without Docker, which looks exactly like a real bug.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.seatlock.support.DockerCheck#dockerIsAvailable")
public @interface IntegrationTest {
}
