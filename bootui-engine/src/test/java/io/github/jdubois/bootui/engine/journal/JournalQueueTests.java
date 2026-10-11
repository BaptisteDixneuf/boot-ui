package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JournalQueueTests {

    @Test
    void keepsTheReservedShareForFailedOrSlowEventsAndTakesEventsInOrder() throws Exception {
        JournalQueue queue = new JournalQueue(4, 2, null, null);

        assertThat(accepted(queue.offer(event(1, false), false))).isTrue();
        assertThat(accepted(queue.offer(event(2, false), false))).isTrue();
        assertThat(queue.offer(event(3, false), false))
                .as("routine events stop at the routine limit")
                .isEqualTo(JournalQueue.FULL);
        assertThat(accepted(queue.offer(event(4, true), true))).isTrue();
        assertThat(accepted(queue.offer(event(5, true), true))).isTrue();
        assertThat(queue.offer(event(6, true), true)).isEqualTo(JournalQueue.FULL);
        assertThat(queue.size()).isEqualTo(4);

        assertThat(poll(queue, 0, TimeUnit.MILLISECONDS).epochMillis()).isEqualTo(1);
        List<RuntimeEvent> batch = new ArrayList<>();
        assertThat(drain(queue, batch, 2)).isEqualTo(2);
        assertThat(accepted(queue.offer(event(7, false), false))).isTrue();
        assertThat(drain(queue, batch, 10)).isEqualTo(2);
        assertThat(batch).extracting(RuntimeEvent::epochMillis).containsExactly(2L, 4L, 5L, 7L);
        assertThat(poll(queue, 0, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aPauseEndsAsSoonAsTheQueueHoldsHalfItsRoutineShare() throws Exception {
        JournalQueue queue = new JournalQueue(10, 8, null, null);
        for (int i = 0; i < 3; i++) {
            queue.offer(event(i, false), false);
        }
        AtomicReference<Long> pausedNanos = new AtomicReference<>();
        Thread dispatcher = new Thread(() -> {
            long started = System.nanoTime();
            try {
                queue.awaitFilling(TimeUnit.SECONDS.toNanos(30));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            pausedNanos.set(System.nanoTime() - started);
        });
        dispatcher.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(dispatcher.getState())
                .as("three of a routine share of eight do not end the pause")
                .isEqualTo(Thread.State.TIMED_WAITING);

        queue.offer(event(3, false), false);

        dispatcher.join(5_000);
        assertThat(dispatcher.isAlive())
                .as("the fourth offer signals the waiting dispatcher")
                .isFalse();
        assertThat(pausedNanos.get()).isLessThan(TimeUnit.SECONDS.toNanos(5));
        long started = System.nanoTime();
        queue.offer(event(4, false), false);
        queue.awaitFilling(TimeUnit.SECONDS.toNanos(30));
        assertThat(System.nanoTime() - started)
                .as("a queue already past half its share is not waited on")
                .isLessThan(TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void aPauseEndsWhenTheQueueIsDetachedOrTheWaiterInterrupted() throws Exception {
        JournalQueue queue = new JournalQueue(10, 8, null, null);
        CompletableFuture<Void> paused = CompletableFuture.runAsync(() -> {
            try {
                queue.awaitFilling(TimeUnit.SECONDS.toNanos(30));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(50);
        queue.detach();
        paused.get(5, TimeUnit.SECONDS);

        JournalQueue other = new JournalQueue(10, 8, null, null);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                other.awaitFilling(TimeUnit.SECONDS.toNanos(30));
            } catch (InterruptedException ex) {
                thrown.set(ex);
            }
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(5_000);
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    void anAcceptedEventIsCountedBeforeTheDispatcherCanTakeIt() throws Exception {
        AtomicReference<JournalQueue> self = new AtomicReference<>();
        AtomicReference<CompletableFuture<Integer>> dispatcher = new AtomicReference<>();
        AtomicReference<Boolean> takenWhileCounting = new AtomicReference<>();
        List<Integer> counted = new ArrayList<>();
        JournalQueue queue = new JournalQueue(4, 4, null, source -> {
            counted.add(source);
            if (dispatcher.get() == null) {
                CompletableFuture<Integer> taking =
                        CompletableFuture.supplyAsync(() -> self.get().drainTo(new RuntimeEvent[10]));
                dispatcher.set(taking);
                try {
                    taking.get(100, TimeUnit.MILLISECONDS);
                    takenWhileCounting.set(true);
                } catch (java.util.concurrent.TimeoutException ex) {
                    takenWhileCounting.set(false);
                } catch (Exception ex) {
                    takenWhileCounting.set(true);
                }
            }
        });
        self.set(queue);

        assertThat(queue.offer(event(1, false), false)).isEqualTo(JournalQueue.ACCEPTED);

        assertThat(takenWhileCounting.get())
                .as("the dispatcher cannot take the event until it is counted")
                .isFalse();
        assertThat(dispatcher.get().get(5, TimeUnit.SECONDS))
                .as("then takes it")
                .isEqualTo(1);
        for (int i = 2; i <= 5; i++) {
            assertThat(queue.offer(event(i, false), false)).isEqualTo(JournalQueue.ACCEPTED);
        }
        assertThat(queue.offer(event(6, true), true)).isEqualTo(JournalQueue.FULL);
        assertThat(counted)
                .as("a refused event is not counted as accepted")
                .hasSize(5)
                .containsOnly(JournalSource.SQL.ordinal());
    }

    @Test
    void routineEventsNeverOvershootTheirShareUnderConcurrentOffers() throws Exception {
        int producers = 8;
        int perProducer = 2_000;
        JournalQueue queue = new JournalQueue(1_000, 600, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int accepted = 0;
                    for (int i = 0; i < perProducer; i++) {
                        int outcome = queue.offer(event(i, false), false);
                        if (outcome == JournalQueue.ACCEPTED) {
                            accepted++;
                        }
                    }
                    return accepted;
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Integer> future : futures) {
                accepted += future.get(30, TimeUnit.SECONDS);
            }
            assertThat(accepted).isEqualTo(600);
            assertThat(queue.size()).isEqualTo(600);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDetachedQueueDropsWhatItHeldAndTakesNothingMore() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null, null);
        queue.offer(event(1, false), false);
        queue.offer(event(2, false), false);
        queue.offer(event(3, false), false);
        List<RuntimeEvent> taken = new ArrayList<>();
        drain(queue, taken, 1);
        queue.offer(event(4, false), false);
        queue.offer(event(5, false), false);

        JournalQueue.Detached dropped = queue.detach();

        assertThat(dropped.count()).isEqualTo(4);
        List<Long> visited = new ArrayList<>();
        dropped.forEach(event -> visited.add(event.epochMillis()));
        assertThat(visited).as("what it held, oldest first, across the wrap").containsExactly(2L, 3L, 4L, 5L);
        assertThat(queue.detach().count())
                .as("only the first detach drops events")
                .isZero();
        assertThat(queue.size()).isZero();
        assertThat(queue.offer(event(3, true), true)).isEqualTo(JournalQueue.DETACHED);
        assertThat(queue.drainTo(new RuntimeEvent[10])).isZero();
        assertThat(poll(queue, 30, TimeUnit.SECONDS))
                .as("a detached queue never makes the dispatcher wait")
                .isNull();
    }

    @Test
    void aSealedQueueTakesNothingMoreButKeepsWhatItHeldForTheLastDrain() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null, null);
        queue.offer(event(1, false), false);

        queue.seal();

        assertThat(queue.offer(event(2, true), true)).isEqualTo(JournalQueue.DETACHED);
        List<RuntimeEvent> batch = new ArrayList<>();
        assertThat(drain(queue, batch, 10)).isEqualTo(1);
        assertThat(batch).extracting(RuntimeEvent::epochMillis).containsExactly(1L);
    }

    @Test
    void aClosedJournalsQueueRefusesEveryEventAndIsNeverDetached() {
        JournalQueue queue = JournalQueue.closed();

        assertThat(queue.offer(event(1, true), true)).isEqualTo(JournalQueue.FULL);
        assertThat(queue.detach().count()).isZero();
        assertThat(queue.offer(event(2, false), false))
                .as("never detached, so an offer is never retried forever")
                .isEqualTo(JournalQueue.FULL);
        assertThat(queue.refusing()).isTrue();
    }

    @Test
    void detachingWakesADispatcherWaitingOnTheQueue() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null, null);
        AtomicReference<RuntimeEvent> polled = new AtomicReference<>(event(0, false));
        Thread dispatcher = new Thread(() -> {
            try {
                polled.set(poll(queue, 30, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        dispatcher.start();
        while (dispatcher.getState() != Thread.State.TIMED_WAITING && dispatcher.isAlive()) {
            Thread.onSpinWait();
        }

        queue.detach();
        dispatcher.join(5_000);

        assertThat(dispatcher.isAlive()).isFalse();
        assertThat(polled.get()).isNull();
    }

    @Test
    void bulkPollIsBoundedAndWrapsWithoutOverwritingTheUnusedBatchTail() throws Exception {
        JournalQueue queue = new JournalQueue(5, 5, null, null);
        for (int i = 1; i <= 5; i++) {
            queue.offer(event(i, false), false);
        }
        RuntimeEvent[] first = new RuntimeEvent[3];
        assertThat(queue.poll(first, 0, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(first).extracting(RuntimeEvent::epochMillis).containsExactly(1L, 2L, 3L);
        for (int i = 6; i <= 8; i++) {
            assertThat(queue.offer(event(i, false), false)).isEqualTo(JournalQueue.ACCEPTED);
        }
        RuntimeEvent sentinel = event(-1, false);
        RuntimeEvent[] wrapped = new RuntimeEvent[7];
        Arrays.fill(wrapped, sentinel);

        assertThat(queue.poll(wrapped, 0, TimeUnit.SECONDS)).isEqualTo(5);

        assertThat(Arrays.copyOf(wrapped, 5))
                .extracting(RuntimeEvent::epochMillis)
                .containsExactly(4L, 5L, 6L, 7L, 8L);
        assertThat(wrapped[5]).isSameAs(sentinel);
        assertThat(wrapped[6]).isSameAs(sentinel);
        assertThat(queue.size()).isZero();
        assertThat(queue.offer(event(9, false), false)).isEqualTo(JournalQueue.ACCEPTED);
        assertThat(poll(queue, 0, TimeUnit.SECONDS).epochMillis()).isEqualTo(9);
    }

    @Test
    void anEmptyToNonemptyTransitionWakesTheDispatcherAfterEachDrain() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null, null);
        for (int cycle = 1; cycle <= 2; cycle++) {
            RuntimeEvent[] batch = new RuntimeEvent[4];
            AtomicReference<Integer> size = new AtomicReference<>();
            Thread dispatcher = new Thread(() -> {
                try {
                    size.set(queue.poll(batch, 30, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                dispatcher.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (dispatcher.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertThat(dispatcher.getState()).isEqualTo(Thread.State.TIMED_WAITING);
                assertThat(queue.offer(event(cycle, false), false)).isEqualTo(JournalQueue.ACCEPTED);
                dispatcher.join(5_000);
                assertThat(dispatcher.isAlive()).isFalse();
                assertThat(size.get()).isEqualTo(1);
                assertThat(batch[0].epochMillis()).isEqualTo(cycle);
            } finally {
                dispatcher.interrupt();
                dispatcher.join(5_000);
            }
        }
    }

    private static int drain(JournalQueue queue, List<RuntimeEvent> output, int max) {
        RuntimeEvent[] batch = new RuntimeEvent[max];
        int size = queue.drainTo(batch);
        output.addAll(Arrays.asList(batch).subList(0, size));
        return size;
    }

    private static RuntimeEvent poll(JournalQueue queue, long timeout, TimeUnit unit) throws InterruptedException {
        RuntimeEvent[] batch = new RuntimeEvent[1];
        return queue.poll(batch, timeout, unit) == 0 ? null : batch[0];
    }

    private static boolean accepted(int outcome) {
        return outcome == JournalQueue.ACCEPTED;
    }

    private static RuntimeEvent event(long epochMillis, boolean failedOrSlow) {
        return RuntimeEvent.of(JournalSource.SQL, epochMillis, 1, null, null, null, failedOrSlow, null);
    }
}
