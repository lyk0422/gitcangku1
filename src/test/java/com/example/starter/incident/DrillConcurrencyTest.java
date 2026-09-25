package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.CancelRequest;
import com.example.starter.incident.dto.Requests.CleanupRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Responses.CleanupView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 演练清理与演练域写操作的并发裁决测试（真实 H2，MODE=MySQL）。
 * 依据行锁与事务提交顺序：清理先提交则后续写 404；写先提交则清理重新校验后
 * 决定成功删除或 422 拒绝。用栅栏协调真实并发，并断言最终状态与裁决结果一致。
 */
@SpringBootTest
class DrillConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_notifications");
        jdbc.update("DELETE FROM incident_dependencies");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM drill_batches");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isStatus(Object result, int status) {
        return result instanceof ApiException e && e.status().value() == status;
    }

    private void reportDrill(String batchKey, String incidentKey) {
        service.report(new ReportRequest(incidentKey, "S3", "演练并发", "reporter-drill",
                "drill-secret", batchKey));
    }

    /**
     * 清理与“取消（进入终态）”并发：
     * 取消总会成功；清理要么在取消提交后重校验为终态而成功删除，
     * 要么先拿到事件锁看到未终结而 422。两种裁决都要求最终状态自洽。
     */
    @Test
    void cleanupVersusCancel_commitOrderArbitration() throws Exception {
        String batch = "BATCH-CC-" + UUID.randomUUID();
        String ik = "CC-1";
        reportDrill(batch, ik);

        List<Object> results = runConcurrently(List.of(
                () -> service.cleanupDrillBatch(new CleanupRequest(key(), batch)),
                () -> service.cancelDrill(ik, "reporter-drill", new CancelRequest(key()))));

        long cleanupSuccess = results.stream().filter(CleanupView.class::isInstance).count();
        long cleanup422 = results.stream().filter(r -> isStatus(r, 422)).count();
        long cancelSuccess = results.stream().filter(IncidentView.class::isInstance).count();
        assertThat(cancelSuccess).isEqualTo(1);
        assertThat(cleanupSuccess + cleanup422).isEqualTo(1);

        var batchView = service.getDrillBatch(batch);
        if (cleanupSuccess == 1) {
            // 写（取消）先提交：清理重校验见终态后成功原子删除
            assertThat(batchView.cleaned()).isTrue();
            assertThat(batchView.incidents()).isEmpty();
            // 清理先于任何后续写生效：事件已不存在
            assertThat(service.list(true).stream().noneMatch(i -> ik.equals(i.incidentKey()))).isTrue();
        } else {
            // 清理先判定未终结而 422：取消生效，事件以 CANCELLED 保留且批次未清理
            assertThat(batchView.cleaned()).isFalse();
            IncidentView remaining = service.get(Domain.DRILL, ik);
            assertThat(remaining.status()).isEqualTo("CANCELLED");
            // 此时全部终态，重新清理必须成功
            CleanupView retry = service.cleanupDrillBatch(new CleanupRequest(key(), batch));
            assertThat(retry.deletedIncidentCount()).isEqualTo(1);
        }
    }

    /**
     * 清理与“往批次新报演练事件”并发：两者经批次登记行锁串行化，恰有一方成功，
     * 另一方 422；不会出现既清理成功又残留未终结新事件的中间态。
     */
    @Test
    void cleanupVersusNewReport_exactlyOneWinsAndStateConsistent() throws Exception {
        String batch = "BATCH-CR-" + UUID.randomUUID();
        reportDrill(batch, "CR-1");
        service.cancelDrill("CR-1", "reporter-drill", new CancelRequest(key()));

        List<Object> results = runConcurrently(List.of(
                () -> service.cleanupDrillBatch(new CleanupRequest(key(), batch)),
                (Callable<Object>) () -> {
                    // 与清理同时尝试向同批次新报一个 REPORTED 事件
                    service.report(new ReportRequest("CR-2", "S3", "演练并发新报", "reporter-drill",
                            "drill-secret", batch));
                    return "REPORTED";
                }));

        long cleanupSuccess = results.stream().filter(CleanupView.class::isInstance).count();
        var members = service.getDrillBatch(batch).incidents();

        if (cleanupSuccess == 1) {
            // 清理先提交：新报必须因批次墓碑 422 失败，批次已清空
            assertThat(results).anyMatch(r -> isStatus(r, 422));
            assertThat(service.getDrillBatch(batch).cleaned()).isTrue();
            assertThat(members).isEmpty();
            // 批次标识不可复用
            assertThatThrownBy(() -> reportDrill(batch, "CR-3"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.status().value()).isEqualTo(422));
        } else {
            // 新报先提交：清理重校验见 REPORTED 未终结而 422，新事件保留
            assertThat(results).anyMatch(r -> isStatus(r, 422));
            assertThat(service.getDrillBatch(batch).cleaned()).isFalse();
            assertThat(members).extracting(IncidentView::incidentKey).contains("CR-2");
        }
    }

    /**
     * 清理先提交后，针对已删事件的后续演练写一律 404（清理先提交 → 后续写 404）。
     */
    @Test
    void cleanupCommitsFirst_subsequentDrillWrite_404() throws Exception {
        String batch = "BATCH-CW-" + UUID.randomUUID();
        reportDrill(batch, "CW-1");
        service.cancelDrill("CW-1", "reporter-drill", new CancelRequest(key()));
        CleanupView cleaned = service.cleanupDrillBatch(new CleanupRequest(key(), batch));
        assertThat(cleaned.deletedIncidentCount()).isEqualTo(1);

        // 并发也无妨：事件行已删除，所有演练写都 404
        List<Object> results = runConcurrently(List.of(
                () -> service.cancelDrill("CW-1", "reporter-drill", new CancelRequest(key())),
                (Callable<Object>) () -> {
                    service.takeover(Domain.DRILL, "CW-1", "alice",
                            new com.example.starter.incident.dto.Requests.TakeoverRequest(key()));
                    return "OK";
                }));
        assertThat(results).allSatisfy(r -> assertThat(isStatus(r, 404)).isTrue());
    }

    /**
     * 同一 cleanupKey 并发重放：只有一次清理生效，其余重放首次结果，删除计数一致。
     */
    @Test
    void concurrentSameCleanupKey_singleEffect() throws Exception {
        String batch = "BATCH-CK-" + UUID.randomUUID();
        reportDrill(batch, "CK-1");
        service.cancelDrill("CK-1", "reporter-drill", new CancelRequest(key()));
        String cleanupKey = key();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.cleanupDrillBatch(new CleanupRequest(cleanupKey, batch)));
        }
        List<Object> results = runConcurrently(tasks);
        List<CleanupView> views = results.stream().filter(CleanupView.class::isInstance)
                .map(CleanupView.class::cast).toList();
        assertThat(views).isNotEmpty();
        assertThat(views).allSatisfy(v -> {
            assertThat(v.batchKey()).isEqualTo(batch);
            assertThat(v.deletedIncidentCount()).isEqualTo(1);
        });
        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(service.getDrillBatch(batch).cleaned()).isTrue();
    }
}
