package com.example.starter.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 服务层真实 H2 数据库测试：主流程、拒绝分支、幂等与并发边界。
 * 使用独立命名内存库 bridge_svc_test，结束后 SHUTDOWN 释放。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BridgePermitServiceTest {

    private static final Instant T09 = Instant.parse("2026-01-01T09:00:00Z");

    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final MutableClock clock;
    private final BridgePermitService service;
    private final PermitRepository permitRepository;
    private final RejectionRepository rejectionRepository;

    BridgePermitServiceTest() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver"); ds.setUrl("jdbc:h2:mem:bridge_svc_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        ds.setUsername("sa");
        this.dataSource = ds;
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("data.sql"));
        }
        JdbcClient jdbc = JdbcClient.create(ds);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        this.clock = new MutableClock(T09);
        this.permitRepository = new PermitRepository(jdbc);
        this.rejectionRepository = new RejectionRepository(jdbc);
        this.service = new BridgePermitService(new BridgeConfigRepository(jdbc),
                permitRepository, rejectionRepository, clock);
    }

    @BeforeEach
    void cleanData() {
        tx.executeWithoutResult(s -> {
            JdbcClient.create(dataSource).sql("DELETE FROM bridge_rejection").update();
            JdbcClient.create(dataSource).sql("DELETE FROM bridge_permit").update();
        });
        clock.set(T09);
    }

    @AfterAll
    void shutdownDatabase() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var st = conn.createStatement()) {
            st.execute("SHUTDOWN");
        }
    }

    private ApplyResult apply(String requestId, String plateNo, long massKg, Instant plannedEntryAt) {
        return tx.execute(s -> service.apply(requestId, plateNo, massKg, plannedEntryAt));
    }

    private BridgePermit enter(long id, String requestId, Instant at) {
        return tx.execute(s -> service.enter(id, requestId, at));
    }

    private BridgePermit exit(long id, String requestId, Instant at) {
        return tx.execute(s -> service.exit(id, requestId, at));
    }

    private BridgePermit cancel(long id, String requestId) {
        return tx.execute(s -> service.cancel(id, requestId));
    }

    private static long approvedId(ApplyResult result) {
        return ((ApplyResult.Approved) result).permit().id();
    }
    @Test
    void mainFlowApplyEnterExit() {
        ApplyResult result = apply("req-1", "PLATE-A", 34_000, T09);
        ApplyResult.Approved approved = (ApplyResult.Approved) result;
        assertEquals(false, approved.replayed());
        BridgePermit permit = approved.permit();
        assertEquals(PermitStatus.APPROVED, permit.status());
        assertEquals(T09, permit.windowStart());
        assertEquals(T09.plusSeconds(600), permit.windowEnd());

        BridgePermit entered = enter(permit.id(), "enter-1", T09);
        assertEquals(PermitStatus.ENTERED, entered.status());
        assertEquals(T09, entered.enteredAt());

        BridgePermit exited = exit(permit.id(), "exit-1", T09.plusSeconds(720));
        assertEquals(PermitStatus.EXITED, exited.status());
        assertEquals(T09.plusSeconds(720), exited.exitedAt());
    }

    @Test
    void overweightRejectedAndKeyNotConsumed() {
        ApplyResult rejected = apply("req-ow", "PLATE-B", 36_000, T09);
        ApplyResult.Rejected rejection = (ApplyResult.Rejected) rejected;
        assertEquals(RejectReason.OVERWEIGHT, rejection.reason());
        assertTrue(rejection.detail().contains("35000"));

        List<RejectionRecord> rejections = tx.execute(s -> service.listRejections());
        assertEquals(1, rejections.size());
        assertEquals("req-ow", rejections.get(0).requestId());

        ApplyResult retry = apply("req-ow", "PLATE-B", 34_000, T09);
        assertTrue(retry instanceof ApplyResult.Approved);
    }

    @Test
    void timeRegressionRejected() {
        ApplyResult rejected = apply("req-past", "PLATE-C", 10_000, T09.minusSeconds(60));
        assertEquals(RejectReason.TIME_REGRESSION, ((ApplyResult.Rejected) rejected).reason());
        assertEquals(0, tx.execute(s -> service.listPermits()).size());
    }

    @Test
    void windowOverlapRejected() {
        apply("req-a", "PLATE-A", 34_000, T09);
        ApplyResult rejected = apply("req-b", "PLATE-B", 20_000, T09.plusSeconds(300));
        ApplyResult.Rejected rejection = (ApplyResult.Rejected) rejected;
        assertEquals(RejectReason.WINDOW_OVERLAP, rejection.reason());
        assertTrue(rejection.detail().contains("PLATE-A"));
        assertEquals(1, tx.execute(s -> service.listPermits()).size());
    }

    @Test
    void replaySameParamsAndConflictOnDifferentParams() {
        ApplyResult first = apply("req-r", "PLATE-A", 34_000, T09);
        ApplyResult replay = apply("req-r", "PLATE-A", 34_000, T09);
        assertTrue(((ApplyResult.Approved) replay).replayed());
        assertEquals(approvedId(first), approvedId(replay));
        assertEquals(1, tx.execute(s -> service.listPermits()).size());

        BridgeApiException ex = assertThrows(BridgeApiException.class,
                () -> apply("req-r", "PLATE-X", 34_000, T09));
        assertEquals(409, ex.status());
        assertEquals("REQUEST_ID_CONFLICT", ex.code());
    }

    @Test
    void enteredPermitBlocksUntilActualExit() {
        long id = approvedId(apply("req-a", "PLATE-A", 34_000, T09));
        enter(id, "enter-1", T09);

        clock.set(T09.plusSeconds(1800));
        ApplyResult blocked = apply("req-b", "PLATE-B", 20_000, T09.plusSeconds(1800));
        assertEquals(RejectReason.WINDOW_OVERLAP, ((ApplyResult.Rejected) blocked).reason());

        exit(id, "exit-1", T09.plusSeconds(1900));
        clock.set(T09.plusSeconds(1900));
        ApplyResult afterExit = apply("req-b", "PLATE-B", 20_000, T09.plusSeconds(1900));
        assertTrue(afterExit instanceof ApplyResult.Approved);
    }
    @Test
    void cancelReleasesWindowAndRejectsAfterEnter() {
        long id = approvedId(apply("req-a", "PLATE-A", 34_000, T09));
        BridgePermit cancelled = cancel(id, "cancel-1");
        assertEquals(PermitStatus.CANCELLED, cancelled.status());

        ApplyResult second = apply("req-b", "PLATE-B", 20_000, T09);
        assertTrue(second instanceof ApplyResult.Approved);

        BridgePermit replayed = cancel(id, "cancel-1");
        assertEquals(PermitStatus.CANCELLED, replayed.status());
        BridgeApiException mismatch = assertThrows(BridgeApiException.class,
                () -> cancel(id, "cancel-2"));
        assertEquals(409, mismatch.status());

        long enteredId = approvedId(apply("req-c", "PLATE-C", 10_000, T09.plusSeconds(1200)));
        enter(enteredId, "enter-c", T09.plusSeconds(1200));
        BridgeApiException enteredCancel = assertThrows(BridgeApiException.class,
                () -> cancel(enteredId, "cancel-c"));
        assertEquals(409, enteredCancel.status());
        assertEquals("INVALID_STATE", enteredCancel.code());
    }

    @Test
    void enterOnlyWithinWindow() {
        long id = approvedId(apply("req-a", "PLATE-A", 34_000, T09));
        BridgeApiException tooEarly = assertThrows(BridgeApiException.class,
                () -> enter(id, "enter-e", T09.minusSeconds(1)));
        assertEquals("OUTSIDE_WINDOW", tooEarly.code());
        BridgeApiException tooLate = assertThrows(BridgeApiException.class,
                () -> enter(id, "enter-l", T09.plusSeconds(600)));
        assertEquals("OUTSIDE_WINDOW", tooLate.code());
        BridgePermit entered = enter(id, "enter-ok", T09.plusSeconds(599));
        assertEquals(PermitStatus.ENTERED, entered.status());
    }

    @Test
    void exitRequiresEnteredAndNotBeforeEntry() {
        long id = approvedId(apply("req-a", "PLATE-A", 34_000, T09));
        BridgeApiException notEntered = assertThrows(BridgeApiException.class,
                () -> exit(id, "exit-1", T09));
        assertEquals("INVALID_STATE", notEntered.code());

        enter(id, "enter-1", T09.plusSeconds(300));
        BridgeApiException backwards = assertThrows(BridgeApiException.class,
                () -> exit(id, "exit-2", T09.plusSeconds(299)));
        assertEquals("TIME_REGRESSION", backwards.code());
    }

    @Test
    void operationReplayAndHistoryNotOverwritten() {
        long id = approvedId(apply("req-a", "PLATE-A", 34_000, T09));
        enter(id, "enter-1", T09);
        BridgePermit replayedEnter = enter(id, "enter-1", T09.plusSeconds(60));
        assertEquals(T09, replayedEnter.enteredAt());
        BridgeApiException enterMismatch = assertThrows(BridgeApiException.class,
                () -> enter(id, "enter-2", T09));
        assertEquals(409, enterMismatch.status());

        exit(id, "exit-1", T09.plusSeconds(300));
        BridgePermit replayedExit = exit(id, "exit-1", T09.plusSeconds(400));
        assertEquals(T09.plusSeconds(300), replayedExit.exitedAt());
        BridgeApiException exitMismatch = assertThrows(BridgeApiException.class,
                () -> exit(id, "exit-2", T09.plusSeconds(500)));
        assertEquals(409, exitMismatch.status());

        BridgePermit persisted = tx.execute(s -> service.listPermits()).get(0);
        assertEquals(T09, persisted.enteredAt());
        assertEquals(T09.plusSeconds(300), persisted.exitedAt());
    }
    @Test
    void concurrentApplySameWindowAllowsAtMostOne() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                String requestId = "race-" + i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(5, TimeUnit.SECONDS));
                    ApplyResult result = apply(requestId, "PLATE-" + requestId, 20_000, T09);
                    if (result instanceof ApplyResult.Approved) {
                        approved.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, approved.get());
        assertEquals(1, rejected.get());
        assertEquals(1, tx.execute(s -> service.listPermits()).size());
        assertEquals(1, tx.execute(s -> service.listRejections()).size());
    }

    @Test
    void enterCancelRaceProducesSingleTerminalState() throws Exception {
        long id = approvedId(apply("req-a", "PLATE-A", 34_000, T09));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        try {
            Future<?> enterFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                try {
                    enter(id, "enter-1", T09);
                    entered.incrementAndGet();
                } catch (BridgeApiException ex) {
                    assertEquals(409, ex.status());
                }
                return null;
            });
            Future<?> cancelFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                try {
                    cancel(id, "cancel-1");
                    cancelled.incrementAndGet();
                } catch (BridgeApiException ex) {
                    assertEquals(409, ex.status());
                }
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            enterFuture.get(15, TimeUnit.SECONDS);
            cancelFuture.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, entered.get() + cancelled.get());
        PermitStatus expected = entered.get() == 1 ? PermitStatus.ENTERED : PermitStatus.CANCELLED;
        assertEquals(expected, tx.execute(s -> service.listPermits()).get(0).status());
    }
}