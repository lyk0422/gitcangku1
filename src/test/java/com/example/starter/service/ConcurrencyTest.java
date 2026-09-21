package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.PublishedSchedule;
import com.example.starter.support.TestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界：同一版本的并发替换/发布仅一方成功；同一 requestId 并发重试结果一致。
 */
class ConcurrencyTest {

    private TestFixture fx;

    @BeforeEach
    void setUp() {
        fx = new TestFixture();
        fx.grantCoveringDay(TestFixture.ASSET_60S);
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static List<DraftSegment> segments(String id, String start, String end) {
        return List.of(TestFixture.segment(id, TestFixture.ASSET_60S, start, end));
    }

    private static int runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                    successes.incrementAndGet();
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status(), e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return successes.get();
    }

    @Test
    void concurrentReplaceSameVersionOnlyOneWins() throws InterruptedException {
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                segments("s0", "08:00", "08:01"));
        int threads = 8;
        int successes = runConcurrently(threads, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, segments("s1", "10:00", "10:01")));
        assertEquals(1, successes, "并发替换同一版本仅一方成功");
        Draft draft = fx.draftService.get(TestFixture.CHANNEL, TestFixture.DAY);
        assertEquals(2, draft.version());
        assertEquals("s1", draft.segments().get(0).segmentId());
    }

    @Test
    void concurrentPublishSameVersionOnlyOneWins() throws InterruptedException {
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                segments("s1", "10:00", "10:01"));
        int threads = 8;
        int successes = runConcurrently(threads, () -> fx.publishService.publish(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, 0));
        assertEquals(1, successes, "并发发布同一版本仅一方成功");
        PublishedSchedule published = fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY);
        assertEquals(1, published.version());
    }

    @Test
    void concurrentSameRequestIdAllSucceedWithSameResult() throws InterruptedException {
        String requestId = rid();
        int threads = 6;
        // 同一 requestId、相同参数并发重试：全部返回首次结果，版本只增一次
        int successes = runConcurrently(threads, () -> {
            Draft draft = fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY,
                    requestId, 0, segments("s1", "10:00", "10:01"));
            assertEquals(1, draft.version());
        });
        assertEquals(threads, successes);
        assertEquals(1, fx.draftService.get(TestFixture.CHANNEL, TestFixture.DAY).version());
    }

    @Test
    void concurrentCreateOnlyOneDraftVersionOne() throws InterruptedException {
        int threads = 8;
        int successes = runConcurrently(threads, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0, segments("s1", "10:00", "10:01")));
        assertEquals(1, successes, "并发创建草稿仅一方成功");
        assertEquals(1, fx.draftService.get(TestFixture.CHANNEL, TestFixture.DAY).version());
    }
}
