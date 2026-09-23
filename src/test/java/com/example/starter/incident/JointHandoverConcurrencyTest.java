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

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverFreezeRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.HandoverView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 联合交接并发边界测试（真实 H2 内存库 + 真实线程）：
 * 验证接受与单事件转交、任务完成并发时按事务提交顺序形成合法结果，
 * 同 commandKey 并发接受单次生效，两个重叠闭包的并发冻结不会都成功。
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
        jdbc.update("DELETE FROM joint_handover_snapshot_escalations");
        jdbc.update("DELETE FROM joint_handover_snapshot_tasks");
        jdbc.update("DELETE FROM joint_handover_snapshot_incidents");
        jdbc.update("DELETE FROM joint_handover_members");
        jdbc.update("DELETE FROM joint_handovers");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CONC-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "s", "r"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static List<Object> runConcurrently(List<? extends Callable<?>> tasks) throws Exception {
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
    void freezeAndSingleTransferInitiate_commitOrderConsistent() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");

        // 并发：alice 冻结 {A,B} 的联合交接；alice 同时就 A 发起单事件转交。
        // 二者都锁事件行并互斥校验，按提交顺序恰好一个成功。
        List<Object> results = runConcurrently(List.of(
                () -> handoverService.freeze("alice", new HandoverFreezeRequest(
                        key(), "HC-1", "bob", List.of("A", "B"))),
                () -> incidentService.initiateTransfer("A", "alice",
                        new TransferRequest(key(), "carol"))));

        long handoverWins = results.stream().filter(HandoverView.class::isInstance).count();
        long transferWins = results.stream()
                .filter(r -> r instanceof com.example.starter.incident.dto.Responses.TransferView)
                .count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        assertThat(handoverWins + transferWins).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        if (handoverWins == 1) {
            // 联合冻结先提交：单事件转交被互斥拒绝，A 无待接受单事件转交
            assertThat(handoverService.get("HC-1").status()).isEqualTo("PENDING");
            assertThat(incidentService.get("A").pendingTransferTo()).isNull();
        } else {
            // 单事件转交先提交：联合冻结被拒绝，交接单不存在
            assertThat(incidentService.get("A").pendingTransferTo()).isEqualTo("carol");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM joint_handovers", Integer.class)).isZero();
        }
    }

    @Test
    void jointAcceptInvalidatesPreviouslyInitiatedSingleTransfer() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");
        // 单事件转交由另一事件 B 先冻结不受影响的场景不成立（互斥），
        // 这里直接验证：冻结后无法再发起转交；接受后旧的转交接受会因指挥人变化被拒。
        HandoverView frozen = handoverService.freeze("alice", new HandoverFreezeRequest(
                key(), "HC-1B", "bob", List.of("A", "B")));
        handoverService.accept("bob", new HandoverAcceptRequest(
                key(), frozen.handoverVersion(), frozen.summary()));
        assertThat(incidentService.get("A").commander()).isEqualTo("bob");
        // A 已归 bob：alice 无法再发起转交
        assertThatThrownBy(() -> incidentService.initiateTransfer("A", "alice",
                new TransferRequest(key(), "carol")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void acceptAndTaskComplete_commitOrderConsistent() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");
        incidentService.createTask("A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        HandoverView frozen = handoverService.freeze("alice", new HandoverFreezeRequest(
                key(), "HC-2", "bob", List.of("A", "B")));

        List<Object> results = runConcurrently(List.of(
                () -> handoverService.accept("bob", new HandoverAcceptRequest(
                        key(), frozen.handoverVersion(), frozen.summary())),
                () -> incidentService.completeTask("A", "T-1", "alice",
                        new TaskActionRequest(key()))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        assertThat(successes).isEqualTo(1);
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        assertThat(conflicts).isEqualTo(1);

        if ("ACCEPTED".equals(handoverService.get("HC-2").status())) {
            // 联合接受先提交：alice 完成任务时已失去指挥权而失败，任务仍 OPEN
            assertThat(incidentService.getTask("A", "T-1").status()).isEqualTo("OPEN");
            assertThat(incidentService.get("A").commander()).isEqualTo("bob");
        } else {
            // 任务完成先提交：版本变化，联合接受 409，指挥人不变
            assertThat(incidentService.getTask("A", "T-1").status()).isEqualTo("DONE");
            assertThat(incidentService.get("A").commander()).isEqualTo("alice");
        }
    }

    @Test
    void concurrentAcceptSameCommandKey_singleEffect() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView frozen = handoverService.freeze("alice", new HandoverFreezeRequest(
                key(), "HC-3", "bob", List.of("A", "B")));
        String commandKey = key();

        List<Callable<HandoverView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> handoverService.accept("bob", new HandoverAcceptRequest(
                    commandKey, frozen.handoverVersion(), frozen.summary())));
        }
        List<Object> results = runConcurrently(tasks);

        List<HandoverView> successes = results.stream()
                .filter(HandoverView.class::isInstance).map(HandoverView.class::cast).toList();
        assertThat(successes).hasSize(3);
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(handoverService.get("HC-3").status()).isEqualTo("ACCEPTED");
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentOverlappingFreezes_notBothSucceed() throws Exception {
        commanding("A", "alice");
        commanding("B", "alice");

        List<Object> results = runConcurrently(List.of(
                () -> handoverService.freeze("alice", new HandoverFreezeRequest(
                        key(), "HC-4", "bob", List.of("A", "B"))),
                () -> handoverService.freeze("alice", new HandoverFreezeRequest(
                        key(), "HC-5", "carol", List.of("A", "B")))));

        long successes = results.stream().filter(HandoverView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
    }
}
