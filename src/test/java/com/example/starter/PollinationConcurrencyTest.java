package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.domain.ConfirmationResult;
import com.example.starter.domain.Inflorescence;
import com.example.starter.domain.OperationStatus;
import com.example.starter.domain.PollinationOperation;
import com.example.starter.service.ApiException;
import com.example.starter.service.PollinationService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发集成测试：真实 H2 库上由两个线程同时提交，验证同一花序只产生一份
 * 有效授粉操作，撤销与确认竞争时只落一个终态。全部设超时，不靠休眠判定。
 */
@SpringBootTest
@Import(TestDatabaseCleanup.class)
class PollinationConcurrencyTest {

    private static final Instant OPEN = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant IN_WINDOW = Instant.parse("2026-10-05T09:59:00Z");

    @Autowired
    private PollinationService service;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pollination_confirmation");
        jdbc.update("DELETE FROM pollination_operation");
        jdbc.update("DELETE FROM inflorescence");
        jdbc.update("DELETE FROM pollen_batch");
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    /** 并发执行两个任务，等两个线程都就绪后同时放行。 */
    private <T> List<Outcome<T>> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Outcome<T>> wrappedFirst = () -> run(ready, start, first);
        Callable<Outcome<T>> wrappedSecond = () -> run(ready, start, second);
        Future<Outcome<T>> f1 = pool.submit(wrappedFirst);
        Future<Outcome<T>> f2 = pool.submit(wrappedSecond);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
    }

    private static <T> Outcome<T> run(CountDownLatch ready, CountDownLatch start, Callable<T> task)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            return new Outcome<>(task.call(), null);
        } catch (ApiException e) {
            return new Outcome<>(null, e);
        }
    }

    private record Outcome<T>(T value, ApiException error) {
    }

    @Test
    void twoTechniciansSameMomentProduceSingleEffectiveOperation() throws Exception {
        service.createPollenBatch("PB-C1");
        Inflorescence inf = service.createInflorescence("INF-C1", OPEN, 24);

        List<Outcome<PollinationOperation>> results = race(
                () -> service.pollinate(inf.id(), "req-c-a", "PB-C1", "tech-a", IN_WINDOW),
                () -> service.pollinate(inf.id(), "req-c-b", "PB-C1", "tech-b", IN_WINDOW));

        List<Outcome<PollinationOperation>> successes = results.stream().filter(r -> r.error() == null).toList();
        List<Outcome<PollinationOperation>> conflicts = results.stream().filter(r -> r.error() != null).toList();
        assertEquals(1, successes.size(), "恰好一个操作成功");
        assertEquals(1, conflicts.size(), "另一个返回 409 冲突");
        assertEquals(HttpStatus.CONFLICT, conflicts.get(0).error().status());
        assertEquals("ACTIVE_OPERATION_EXISTS", conflicts.get(0).error().code());

        // 最终数据核对：库里只有一条 EFFECTIVE 操作，花序被它占据
        Integer effective = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pollination_operation WHERE inflorescence_id = ? AND status = 'EFFECTIVE'",
                Integer.class, inf.id());
        assertEquals(1, effective);
        Long activeOp = jdbc.queryForObject(
                "SELECT active_operation_id FROM inflorescence WHERE id = ?", Long.class, inf.id());
        assertNotNull(activeOp);
        assertEquals(successes.get(0).value().id(), activeOp);
    }

    @Test
    void confirmAndRevokeRaceLeavesSingleTerminalState() throws Exception {
        service.createPollenBatch("PB-C2");
        Inflorescence inf = service.createInflorescence("INF-C2", OPEN, 24);
        PollinationOperation op = service.pollinate(inf.id(), "req-c2-op", "PB-C2", "tech-a", IN_WINDOW);

        List<Outcome<Object>> results = race(
                () -> service.confirm(op.id(), "req-c2-conf", ConfirmationResult.SUCCESS,
                        "tech-b", Instant.parse("2026-10-05T10:30:00Z")),
                () -> service.revoke(op.id(), Instant.parse("2026-10-05T10:30:00Z")));

        long successes = results.stream().filter(r -> r.error() == null).count();
        long conflicts = results.stream().filter(r -> r.error() != null
                && r.error().status() == HttpStatus.CONFLICT).count();
        assertEquals(1, successes, "确认与撤销竞争只允许一个成功");
        assertEquals(1, conflicts, "失败方返回 409 终态竞争");

        // 最终数据核对：操作落且仅落一个终态，花序状态与之一致
        String status = jdbc.queryForObject(
                "SELECT status FROM pollination_operation WHERE id = ?", String.class, op.id());
        Long activeOp = jdbc.queryForObject(
                "SELECT active_operation_id FROM inflorescence WHERE id = ?", Long.class, inf.id());
        String fixedBatch = jdbc.queryForObject(
                "SELECT confirmed_pollen_batch_no FROM inflorescence WHERE id = ?", String.class, inf.id());
        if (OperationStatus.CONFIRMED.name().equals(status)) {
            assertEquals("PB-C2", fixedBatch, "确认成功须固化花粉批号");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pollination_confirmation WHERE operation_id = ?",
                    Integer.class, op.id()));
        } else {
            assertEquals(OperationStatus.REVOKED.name(), status);
            assertNull(activeOp, "撤销成功须释放花序");
            assertNull(fixedBatch);
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pollination_confirmation WHERE operation_id = ?",
                    Integer.class, op.id()));
        }
    }
}