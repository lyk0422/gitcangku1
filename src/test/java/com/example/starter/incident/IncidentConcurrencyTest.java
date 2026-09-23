package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发边界测试：验证行锁串行化与幂等键唯一约束下，
 * 并发接管、交接接受与状态推进按事务提交顺序生效，且同键重放安全。
 */
@SpringBootTest
class IncidentConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM proposal_votes");
        jdbc.update("DELETE FROM proposal_roster_entries");
        jdbc.update("DELETE FROM dependency_change_proposals");
        jdbc.update("DELETE FROM incident_dependency_edges");
        jdbc.update("UPDATE dependency_graph_meta SET graph_version = 1");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "INC-" + UUID.randomUUID();
    }

    /**
     * 并发提交一批任务并收集结果（成功值或异常）。
     */
    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
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

    @Test
    void concurrentTakeover_exactlyOneWins() throws Exception {
        String ik = "INC-200";
        service.report(new ReportRequest(ik, "S1", "并发接管", "r"));
        List<Callable<IncidentView>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String actor = "actor-" + i;
            tasks.add(() -> service.takeover(ik, actor, new TakeoverRequest(key())));
        }
        List<Object> results = runConcurrently(tasks);
        long successes = results.stream().filter(IncidentView.class::isInstance).count();
        long illegal = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 422).count();
        assertThat(successes).isEqualTo(1);
        assertThat(illegal).isEqualTo(3);
        // 状态只流转一次
        assertThat(service.history(ik).statusHistory())
                .filteredOn(s -> "COMMANDING".equals(s.toStatus())).hasSize(1);
    }

    @Test
    void concurrentAcceptAndResolve_commitOrderWins() throws Exception {
        String ik = "INC-201";
        service.report(new ReportRequest(ik, "S1", "交接与解决并发", "r"));
        service.takeover(ik, "alice", new TakeoverRequest(key()));
        service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));

        List<Object> results = runConcurrently(List.of(
                () -> service.acceptTransfer(ik, "bob", new TransferAcceptRequest(key())),
                () -> service.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"))));

        long successes = results.stream().filter(IncidentView.class::isInstance).count();
        long failures = results.stream().filter(ApiException.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(failures).isEqualTo(1);

        IncidentView end = service.get(ik);
        var transfers = service.history(ik).transfers();
        if ("RESOLVED".equals(end.status())) {
            // 解决先提交：交接接受必须失败，指挥人不变
            assertThat(end.commander()).isEqualTo("alice");
            assertThat(transfers.get(0).status()).isEqualTo("PENDING");
        } else {
            // 接受先提交：旧指挥人的解决必须失败，状态仍 CONTAINED，指挥人已切换
            assertThat(end.status()).isEqualTo("CONTAINED");
            assertThat(end.commander()).isEqualTo("bob");
            assertThat(transfers.get(0).status()).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void concurrentSameCommandKey_singleEffect() throws Exception {
        String ik = "INC-202";
        service.report(new ReportRequest(ik, "S1", "同键并发", "r"));
        String commandKey = key();
        List<Callable<IncidentView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.takeover(ik, "alice", new TakeoverRequest(commandKey)));
        }
        List<Object> results = runConcurrently(tasks);

        List<IncidentView> successes = results.stream().filter(IncidentView.class::isInstance)
                .map(IncidentView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且接管只生效一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.get(ik).commander()).isEqualTo("alice");
        assertThat(service.history(ik).statusHistory())
                .filteredOn(s -> "COMMANDING".equals(s.toStatus())).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentActions_distinctKeysBothPersist() throws Exception {
        String ik = "INC-203";
        service.report(new ReportRequest(ik, "S3", "并发处置记录", "r"));
        service.takeover(ik, "alice", new TakeoverRequest(key()));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String actionKey = "ACT-" + i;
            tasks.add(() -> service.addAction(ik, "alice", new ActionRequest(key(), actionKey,
                    "NOTE", "记录 " + actionKey, Instant.parse("2026-09-21T08:00:00Z"))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        assertThat(service.history(ik).actions()).hasSize(4);
    }
}
