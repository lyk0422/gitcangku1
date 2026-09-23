package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverInitiateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.HandoverDetailView;
import com.example.starter.incident.dto.Responses.HandoverView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 联合交接并发边界测试：验证行锁按提交顺序串行化接受与任务完成、单事件转交，
 * 快照与切换时状态一致、不允许部分接管；同键并发只生效一次；
 * 重叠闭包的两个交接单只有先提交者可接受。
 */
@SpringBootTest
class JointHandoverConcurrencyTest {

    @Autowired
    private JointHandoverService handoverService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM joint_handover_snapshots");
        jdbc.update("DELETE FROM joint_handover_incidents");
        jdbc.update("DELETE FROM joint_handovers");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "故障 " + incidentKey, "r"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
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
    void acceptVsTaskComplete_commitOrderWinsAndSnapshotConsistent() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");
        // 无阻塞 OPEN 任务：alice 可随时完成；bob 接受会冻结该任务
        incidentService.createTask("A", "alice",
                new TaskCreateRequest(key(), "TA", "G", "t", List.of()));
        HandoverView preview = handoverService.initiate("alice",
                new HandoverInitiateRequest(key(), "H-CC1", "bob", List.of("A", "B")));

        List<Object> results = runConcurrently(List.of(
                () -> handoverService.accept("bob", "H-CC1", new HandoverAcceptRequest(
                        key(), preview.handoverVersion(), preview.summary())),
                () -> incidentService.completeTask("A", "TA", "alice",
                        new TaskActionRequest(key()))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        IncidentView a = incidentService.get("A");
        IncidentView b = incidentService.get("B");
        HandoverDetailView detail = handoverService.detail("H-CC1");
        if ("bob".equals(a.commander())) {
            // 接受先提交：两个事件都切给 bob，alice 的完成因失去指挥权失败，快照含 OPEN 任务 TA
            assertThat(b.commander()).isEqualTo("bob");
            assertThat(detail.handover().status()).isEqualTo("ACCEPTED");
            assertThat(detail.snapshots()).hasSize(2);
            assertThat(detail.snapshots().get(0).openTasks()).singleElement()
                    .satisfies(t -> assertThat(t.taskKey()).isEqualTo("TA"));
            assertThat(incidentService.listTasks("A").tasks().get(0).status()).isEqualTo("OPEN");
        } else {
            // 任务完成先提交：任务版本变化导致接受 409，无任何切换与快照，不允许部分接管
            assertThat(a.commander()).isEqualTo("alice");
            assertThat(b.commander()).isEqualTo("alice");
            assertThat(detail.handover().status()).isEqualTo("PENDING");
            assertThat(detail.snapshots()).isEmpty();
            assertThat(incidentService.listTasks("A").tasks().get(0).status()).isEqualTo("DONE");
        }
    }

    @Test
    void concurrentAcceptsWithSameCommandKey_singleEffect() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView preview = handoverService.initiate("alice",
                new HandoverInitiateRequest(key(), "H-CC2", "bob", List.of("A", "B")));
        String commandKey = key();
        List<Callable<HandoverView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> handoverService.accept("bob", "H-CC2", new HandoverAcceptRequest(
                    commandKey, preview.handoverVersion(), preview.summary())));
        }
        List<Object> results = runConcurrently(tasks);

        List<HandoverView> accepted = results.stream()
                .filter(HandoverView.class::isInstance).map(HandoverView.class::cast).toList();
        assertThat(accepted).hasSize(3);
        assertThat(accepted).allSatisfy(v -> {
            assertThat(v.status()).isEqualTo("ACCEPTED");
            assertThat(v).isEqualTo(accepted.get(0));
        });
        // 指挥权只切换一次（版本从 1 到 2）
        Long versions = jdbc.queryForObject(
                "SELECT version FROM incidents WHERE incident_key = 'A'", Long.class);
        assertThat(versions).isEqualTo(2L);
        Long snapshotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_handover_snapshots s JOIN joint_handovers h"
                        + " ON s.handover_id = h.id WHERE h.handover_key = 'H-CC2'", Long.class);
        assertThat(snapshotCount).isEqualTo(2L);
    }

    @Test
    void overlappingHandovers_onlyFirstCommittedAcceptWins() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView first = handoverService.initiate("alice",
                new HandoverInitiateRequest(key(), "H-OV1", "bob", List.of("A", "B")));
        HandoverView second = handoverService.initiate("alice",
                new HandoverInitiateRequest(key(), "H-OV2", "carol", List.of("A", "B")));

        List<Object> results = runConcurrently(List.of(
                () -> handoverService.accept("bob", "H-OV1", new HandoverAcceptRequest(
                        key(), first.handoverVersion(), first.summary())),
                () -> handoverService.accept("carol", "H-OV2", new HandoverAcceptRequest(
                        key(), second.handoverVersion(), second.summary()))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        assertThat(successes).isEqualTo(1);
        String finalCommander = incidentService.get("A").commander();
        assertThat(incidentService.get("B").commander()).isEqualTo(finalCommander);
        HandoverDetailView d1 = handoverService.detail("H-OV1");
        HandoverDetailView d2 = handoverService.detail("H-OV2");
        if ("bob".equals(finalCommander)) {
            assertThat(d1.handover().status()).isEqualTo("ACCEPTED");
            assertThat(d2.handover().status()).isEqualTo("PENDING");
            assertThat(d2.snapshots()).isEmpty();
        } else {
            assertThat(d2.handover().status()).isEqualTo("ACCEPTED");
            assertThat(d1.handover().status()).isEqualTo("PENDING");
            assertThat(d1.snapshots()).isEmpty();
        }
    }

    @Test
    void concurrentInitiateSameHandoverKey_exactlyOneWins() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> handoverService.initiate("alice", new HandoverInitiateRequest(
                    key(), "H-DUPK", "bob", List.of("A", "B"))));
        }
        List<Object> results = runConcurrently(tasks);
        long successes = results.stream().filter(HandoverView.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(2);
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_handovers WHERE handover_key = 'H-DUPK'", Long.class);
        assertThat(rows).isEqualTo(1L);
    }
}
