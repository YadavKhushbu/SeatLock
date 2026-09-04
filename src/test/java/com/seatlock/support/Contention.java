package com.seatlock.support;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs N attempts genuinely simultaneously and reports what happened.
 *
 * <p>The starting gate matters. Submitting N tasks to a pool staggers them by
 * however long it takes to hand each one to a thread, which on a fast machine is
 * long enough for most of them to finish before the last one starts. A test
 * written that way passes whether or not the locking works, because it never
 * actually creates contention. Here every thread is parked on the same latch and
 * released in one go.
 */
public final class Contention {

    private Contention() {
    }

    /**
     * @param successes how many attempts returned normally
     * @param failures  the exception class simple names, with counts
     */
    public record Outcome(int successes, List<String> failures) {

        public int failureCount() {
            return failures.size();
        }

        public long countOf(Class<? extends Throwable> type) {
            return failures.stream().filter(f -> f.equals(type.getSimpleName())).count();
        }

        @Override
        public String toString() {
            return successes + " succeeded, " + failures.size() + " failed " + tally();
        }

        private String tally() {
            return failures.stream().distinct()
                    .map(name -> name + "=" + failures.stream().filter(name::equals).count())
                    .toList().toString();
        }
    }

    /**
     * Runs {@code attempts} copies of {@code action}, all released at the same instant.
     *
     * <p>The pool must have one thread per attempt. Every task parks on the same
     * latch, so a pool smaller than the number of tasks deadlocks by
     * construction: the queued tasks never start, never count down, and the gate
     * never opens.
     */
    public static Outcome race(int attempts, ThrowingIntConsumer action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);

        AtomicInteger successes = new AtomicInteger();
        List<String> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < attempts; i++) {
            final int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    action.accept(index);
                    successes.incrementAndGet();
                } catch (Throwable t) {
                    // Unwrap so assertions can talk about the domain exception
                    // rather than whatever proxy wrapper it arrived in.
                    Throwable root = t;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    failures.add(root.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        // Wait until every thread is at the line before firing the gun.
        if (!ready.await(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            throw new IllegalStateException("Threads did not reach the starting gate");
        }
        go.countDown();

        boolean finished = done.await(90, TimeUnit.SECONDS);
        pool.shutdownNow();
        if (!finished) {
            // Almost certainly a deadlock: the test has proved something, just
            // not the thing it hoped to.
            throw new IllegalStateException("Attempts did not finish within 90s; suspect a deadlock");
        }
        return new Outcome(successes.get(), List.copyOf(failures));
    }

    @FunctionalInterface
    public interface ThrowingIntConsumer {
        void accept(int index) throws Exception;
    }
}
