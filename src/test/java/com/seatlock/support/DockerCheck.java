package com.seatlock.support;

import org.testcontainers.DockerClientFactory;

/**
 * Lets the integration tests skip rather than fail when no container runtime is
 * present.
 *
 * <p>A red build on a laptop without Docker running says nothing about the code,
 * and teaches people to ignore red builds. CI always has Docker, so nothing is
 * quietly skipped where it matters.
 */
public final class DockerCheck {

    private static final boolean AVAILABLE = probe();

    private DockerCheck() {
    }

    public static boolean dockerIsAvailable() {
        return AVAILABLE;
    }

    private static boolean probe() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
