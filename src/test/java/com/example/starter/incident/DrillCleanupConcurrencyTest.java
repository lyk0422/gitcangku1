package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.incident.dto.Requests.CleanupRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.CleanupView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 清理与演练写入并发裁决测试（真实 H2 行锁）：
 * 清理先提交 → 排队的演练写入获得批次锁后看到 CLEANED 墓碑返回 404；
 * 演练写入先提交 → 清理取得批次锁后重新校验，新事件未终结返回 422。
 * 另覆盖并发同 cleanupKey 只生效一次且双方重放一致。
 */
@SpringBootTest
class DrillCleanupConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private DrillCleanupService cleanupService;

    @Autowired
    private IncidentRepository incidents;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM drill_cleanups");
        jdbc.update("DELETE FROM drill_batches");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "K-" + UUID.randomUUID();
    }

    private IncidentView drill(String incidentKey, String batch) {
        return service.report(new ReportRequest(incidentKey, "S2", "演练", "r",
                "drill-" + batch, batch));
    }

    private void takeAndClose(String incidentKey) {
        service.takeover(Domain.DRILL, incidentKey, "alice", new TakeoverRequest(key()));
        service.changeStatus(Domain.DRILL, incidentKey, "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus(Domain.DRILL, incidentKey, "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus(Domain.DRILL, incidentKey, "alice", new StatusRequest(key(), "CLOSED"));
    }

    /**
     * 确定性裁决一：清理事务先持有批次行锁并提交，并发的新演练写入在锁上排队，
     * 获锁后读到 CLEANED 墓碑，必须返回 404，且不得创建事件。
     */
    @Test
    void cleanupCommitsFirst_blockedDrillWriteGets404() throws Exception {
        String batch = "batch-FIRST";
        drill("INC-F1", batch);
        takeAndClose("INC-F1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch cleanupInsideLock = new CountDownLatch(1);
        AtomicReference<Throwable> writeError = new AtomicReference<>();
        try {
            // 清理事务：在独立线程内手工控制提交时机，保证先持锁。
            Future<?> cleanupDone = pool.submit(() -> tx.executeWithoutResult(status -> {
                incidents.lockBatch(batch).orElseThrow();
                List<Incident> locked = incidents.lockIncidentsByBatch(batch);
                cleanupInsideLock.countDown();
                try {
                    // 持锁等待并发写入进入并在批次行锁上排队
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                List<Long> ids = locked.stream().map(Incident::id).toList();
                incidents.deleteBatchIncidents(batch, ids);
                incidents.markBatchCleaned(batch, java.time.Instant.now());
                incidents.insertCleanup(key(), batch, ids.size(), "alice", java.time.Instant.now());
            }));

            assertThat(cleanupInsideLock.await(5, TimeUnit.SECONDS)).isTrue();

            // 并发演练写入（另一线程/连接/事务）：此时应在批次行锁上阻塞。
            Future<?> writeFuture = pool.submit(() -> {
                try {
                    service.report(new ReportRequest("INC-F2", "S2", "新演练", "r",
                            "drill-" + batch, batch));
                } catch (Throwable t) {
                    writeError.set(t);
                }
            });
            cleanupDone.get(15, TimeUnit.SECONDS);
            writeFuture.get(15, TimeUnit.SECONDS);

            assertThat(writeError.get()).isInstanceOf(ApiException.class);
            assertThat(((ApiException) writeError.get()).status().value()).isEqualTo(404);
            // 新事件未创建，批次为墓碑
            Integer incidentCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incidents WHERE drill_batch = ?", Integer.class, batch);
            assertThat(incidentCount).isZero();
            String status = jdbc.queryForObject(
                    "SELECT status FROM drill_batches WHERE batch_key = ?", String.class, batch);
            assertThat(status).isEqualTo("CLEANED");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 真实竞态：新演练写入与清理并发，结果必须二选一且数据自洽：
     * 清理先提交→清理成功、写入 404；写入先提交→清理 422（新事件未终结）、批次仍 ACTIVE。
     */
    @Test
    void cleanupVersusNewWrite_commitOrderArbitration_consistentInAllRounds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                String batch = "batch-RACE-" + round;
                drill("INC-SEED-" + round, batch);
                takeAndClose("INC-SEED-" + round);
                String newKey = "INC-NEW-" + round;

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Callable<Object> write = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return service.report(new ReportRequest(newKey, "S2", "新演练", "r",
                                "drill-" + batch, batch));
                    } catch (Exception e) {
                        return e;
                    }
                };
                Callable<Object> cleanup = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return cleanupService.cleanup("alice", new CleanupRequest(key(), batch));
                    } catch (Exception e) {
                        return e;
                    }
                };
                Future<Object> wf = pool.submit(write);
                Future<Object> cf = pool.submit(cleanup);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                Object wr = wf.get(30, TimeUnit.SECONDS);
                Object cr = cf.get(30, TimeUnit.SECONDS);

                String batchStatus = incidents.findBatch(batch).orElseThrow().status().name();
                Integer newIncident = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM incidents WHERE domain='DRILL' AND incident_key = ?",
                        Integer.class, newKey);
                Integer cleanupRows = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM drill_cleanups WHERE batch_key = ?", Integer.class, batch);

                boolean cleanupWon = cr instanceof CleanupView && wr instanceof ApiException w
                        && w.status().value() == 404;
                boolean writeWon = wr instanceof IncidentView && cr instanceof ApiException c
                        && c.status().value() == 422;
                assertThat(cleanupWon || writeWon)
                        .as("清理与写入必须恰好一方获胜: write=%s cleanup=%s", wr, cr).isTrue();

                if (cleanupWon) {
                    assertThat(batchStatus).isEqualTo("CLEANED");
                    assertThat(newIncident).isZero();
                    assertThat(cleanupRows).isEqualTo(1);
                } else {
                    assertThat(batchStatus).isEqualTo("ACTIVE");
                    assertThat(newIncident).isEqualTo(1);
                    assertThat(cleanupRows).isZero();
                    // 写入先提交后，清理重新校验仍可被满足：终结新事件后再次清理成功
                    takeAndClose(newKey);
                    CleanupView again = cleanupService.cleanup("alice", new CleanupRequest(key(), batch));
                    assertThat(again.deletedIncidents()).isEqualTo(2);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 并发相同 cleanupKey：只产生一次清理与一条历史，双方结果一致（重放首次结果）。
     */
    @Test
    void concurrentSameCleanupKey_singleEffectAndEqualReplay() throws Exception {
        String batch = "batch-CK";
        drill("INC-CK1", batch);
        drill("INC-CK2", batch);
        takeAndClose("INC-CK1");
        takeAndClose("INC-CK2");
        String cleanupKey = key();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> task = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return cleanupService.cleanup("alice", new CleanupRequest(cleanupKey, batch));
                } catch (Exception e) {
                    return e;
                }
            };
            Future<Object> f1 = pool.submit(task);
            Future<Object> f2 = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object r1 = f1.get(30, TimeUnit.SECONDS);
            Object r2 = f2.get(30, TimeUnit.SECONDS);

            assertThat(r1).isInstanceOf(CleanupView.class);
            assertThat(r2).isInstanceOf(CleanupView.class);
            assertThat(r1).isEqualTo(r2);
            assertThat(((CleanupView) r1).deletedIncidents()).isEqualTo(2);
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM drill_cleanups WHERE cleanup_key = ?", Integer.class, cleanupKey);
            assertThat(n).isEqualTo(1);
            Integer incidentsLeft = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incidents WHERE domain='DRILL' AND drill_batch = ?",
                    Integer.class, batch);
            assertThat(incidentsLeft).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
