package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.service.AttachmentService;
import com.example.starter.service.BusinessException;
import com.example.starter.service.TagService;
import com.example.starter.service.TransferService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 并发：真实线程同时操作同一标签，按所有权行锁的提交顺序判定，
 * 同一标签任何时刻只能由一个站点持有、只能有一段有效附着。
 */
class ConcurrencyTest extends AbstractDatabaseTest {

    @Autowired
    private TagService tagService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private TransferService transferService;

    /** 并发执行两个操作并收集结果（值或异常），15 秒超时。 */
    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream().map(action -> pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    return (Object) action.call();
                } catch (BusinessException e) {
                    return e;
                }
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();
            return futures.stream().map(f -> {
                try {
                    return f.get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("并发操作超时或失败", e);
                }
            }).toList();
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未退出");
        }
    }

    private long successes(List<Object> results) {
        return results.stream().filter(r -> !(r instanceof BusinessException)).count();
    }

    @Test
    void concurrentAttachSameTagOnlyOneSucceeds() throws Exception {
        tagService.register("T1", "ST-A");
        var results = race(
                () -> attachmentService.attach(UUID.randomUUID().toString(), "T1", "AN-1",
                        "ST-A", Instant.parse("2026-10-04T09:00:00Z")),
                () -> attachmentService.attach(UUID.randomUUID().toString(), "T1", "AN-2",
                        "ST-A", Instant.parse("2026-10-04T09:00:00Z")));
        assertEquals(1, successes(results));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE tag_code = 'T1' AND detached_at IS NULL",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void concurrentAttachSameAnimalOnlyOneSucceeds() throws Exception {
        tagService.register("T1", "ST-A");
        tagService.register("T2", "ST-A");
        var results = race(
                () -> attachmentService.attach(UUID.randomUUID().toString(), "T1", "AN-1",
                        "ST-A", Instant.parse("2026-10-04T09:00:00Z")),
                () -> attachmentService.attach(UUID.randomUUID().toString(), "T2", "AN-1",
                        "ST-A", Instant.parse("2026-10-04T09:00:00Z")));
        assertEquals(1, successes(results));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE animal_code = 'AN-1' AND detached_at IS NULL",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void concurrentConfirmAndCancelExactlyOneWins() throws Exception {
        tagService.register("T1", "ST-A");
        attachmentService.attach(UUID.randomUUID().toString(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T08:00:00Z"));
        attachmentService.detach("T1", "ST-A", Instant.parse("2026-10-04T10:00:00Z"));
        var transfer = transferService.initiate(UUID.randomUUID().toString(), "T1", "ST-A",
                "ST-B", Instant.parse("2026-10-04T10:00:00Z"));

        var results = race(
                () -> transferService.confirm(transfer.id(), "ST-B",
                        Instant.parse("2026-10-04T10:05:00Z")),
                () -> transferService.cancel(transfer.id(), "ST-A",
                        Instant.parse("2026-10-04T10:05:00Z")));
        assertEquals(1, successes(results));
        var loser = results.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast).findFirst().orElseThrow();
        assertEquals(409, loser.status());

        String status = jdbc.queryForObject(
                "SELECT status FROM transfer WHERE id = " + transfer.id(), String.class);
        String holder = tagService.get("T1").stationCode();
        if ("CONFIRMED".equals(status)) {
            assertEquals("ST-B", holder);
        } else {
            assertEquals("CANCELLED", status);
            assertEquals("ST-A", holder);
        }
    }

    @Test
    void concurrentDoubleConfirmOnlyOneSucceeds() throws Exception {
        tagService.register("T1", "ST-A");
        attachmentService.attach(UUID.randomUUID().toString(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T08:00:00Z"));
        attachmentService.detach("T1", "ST-A", Instant.parse("2026-10-04T10:00:00Z"));
        var transfer = transferService.initiate(UUID.randomUUID().toString(), "T1", "ST-A",
                "ST-B", Instant.parse("2026-10-04T10:00:00Z"));

        var results = race(
                () -> transferService.confirm(transfer.id(), "ST-B",
                        Instant.parse("2026-10-04T10:05:00Z")),
                () -> transferService.confirm(transfer.id(), "ST-B",
                        Instant.parse("2026-10-04T10:05:00Z")));
        assertEquals(1, successes(results));
        var loser = results.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast).findFirst().orElseThrow();
        assertEquals(409, loser.status());
        assertInstanceOf(String.class, tagService.get("T1").stationCode());
        assertEquals("ST-B", tagService.get("T1").stationCode());
    }
}