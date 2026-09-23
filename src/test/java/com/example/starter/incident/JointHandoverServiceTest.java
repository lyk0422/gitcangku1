package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverFreezeRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.HandoverSnapshotView;
import com.example.starter.incident.dto.Responses.HandoverSummary;
import com.example.starter.incident.dto.Responses.HandoverView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 联合指挥交接服务测试：覆盖闭包计算与恰好覆盖校验、冻结/接受主流程、
 * 400/403/409/422 失败分支、冻结后变化检测、整体回滚、不可变快照与幂等语义。
 * 全部基于真实 H2（MODE=MySQL）内存库，校验实际 SQL、唯一约束与事务边界。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class JointHandoverServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    @Autowired
    private JointHandoverService handoverService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        ((ControllableClock) clock).setInstant(T0);
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
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "s", "r"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void openTask(String incidentKey, String taskKey, List<String> blockers) {
        incidentService.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "G", "t", blockers));
    }

    private HandoverView freeze(String handoverKey, String actor, String toCommander,
                                List<String> incidentKeys) {
        return handoverService.freeze(actor,
                new HandoverFreezeRequest(key(), handoverKey, toCommander, incidentKeys));
    }

    private HandoverView accept(HandoverView frozen, String actor) {
        return handoverService.accept(actor,
                new HandoverAcceptRequest(key(), frozen.handoverVersion(), frozen.summary()));
    }

    // ---------- 主流程 ----------

    @Test
    void freezeAndAccept_switchesAllIncidentsAndPersistsSnapshot() {
        commanding("A", "alice");
        commanding("B", "alice");
        // A 的 OPEN 任务依赖仍未解决的 B，闭包为 {A,B}
        openTask("A", "T-A", List.of("B"));

        HandoverView frozen = freeze("H-1", "alice", "bob", List.of("A", "B"));

        assertThat(frozen.status()).isEqualTo("PENDING");
        assertThat(frozen.closureIncidentKeys()).containsExactly("A", "B");
        assertThat(frozen.handoverVersion()).hasSize(64);
        assertThat(frozen.summary().incidents()).hasSize(2);
        var incidentA = frozen.summary().incidents().stream()
                .filter(i -> i.incidentKey().equals("A")).findFirst().orElseThrow();
        assertThat(incidentA.commander()).isEqualTo("alice");
        assertThat(incidentA.openTasks()).hasSize(1);
        assertThat(incidentA.openTasks().get(0).blockerKeys()).containsExactly("B");
        assertThat(incidentA.unacknowledgedEscalationVersion()).isNull();
        assertThat(incidentService.get("A").commander()).isEqualTo("alice");

        HandoverView accepted = accept(frozen, "bob");

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.acceptedAt()).isNotNull();
        assertThat(incidentService.get("A").commander()).isEqualTo("bob");
        assertThat(incidentService.get("B").commander()).isEqualTo("bob");

        HandoverSnapshotView snapshot = handoverService.snapshot("H-1");
        assertThat(snapshot.incidents()).hasSize(2);
        assertThat(snapshot.incidents()).allSatisfy(i -> {
            assertThat(i.commander()).isEqualTo("alice");
            assertThat(i.status()).isEqualTo("COMMANDING");
        });
        assertThat(snapshot.tasks()).hasSize(1);
        assertThat(snapshot.tasks().get(0).incidentKey()).isEqualTo("A");
        assertThat(snapshot.tasks().get(0).taskKey()).isEqualTo("T-A");
        assertThat(snapshot.tasks().get(0).blockerKeys()).containsExactly("B");
        assertThat(snapshot.escalations()).isEmpty();

        // 交接历史可查，GET 不写数据
        int handoverRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_handovers", Integer.class);
        assertThat(handoverService.listByIncident("A")).hasSize(1);
        handoverService.get("H-1");
        handoverService.listByIncident("B");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM joint_handovers", Integer.class))
                .isEqualTo(handoverRows);
    }

    @Test
    void closureExpandsTransitivelyAlongUnfinishedBlockers() {
        commanding("A", "alice");
        commanding("B", "alice");
        commanding("C", "alice");
        openTask("A", "T-A", List.of("B"));
        openTask("B", "T-B", List.of("C"));

        HandoverView frozen = freeze("H-2", "alice", "bob", List.of("A", "B", "C"));

        assertThat(frozen.closureIncidentKeys()).containsExactly("A", "B", "C");
        // 快照含 A、B 两个事件的 OPEN 任务
        HandoverSnapshotView snapshotAfterAccept = null;
        accept(frozen, "bob");
        snapshotAfterAccept = handoverService.snapshot("H-2");
        assertThat(snapshotAfterAccept.tasks()).extracting(t -> t.incidentKey() + ":" + t.taskKey())
                .containsExactly("A:T-A", "B:T-B");
    }

    @Test
    void resolvedBlockerDoesNotExpandClosure() {
        commanding("A", "alice");
        commanding("B", "alice");
        commanding("C", "alice");
        // B 进入 CONTAINED：该阻塞关系视为已解除，闭包不会因它扩展到 B
        incidentService.changeStatus("B", "alice", new StatusRequest(key(), "CONTAINED"));
        openTask("A", "T-A", List.of("B"));

        // 提交 {A,C}：若 B 仍算未解除，闭包会包含 B 而报 422；实际闭包恰为 {A,C}，冻结成功
        HandoverView frozen = freeze("H-3", "alice", "bob", List.of("A", "C"));
        assertThat(frozen.closureIncidentKeys()).containsExactly("A", "C");
    }

    // ---------- 422 闭包未覆盖 ----------

    @Test
    void missingClosureIncidents_returns422WithMissingList() {
        commanding("A", "alice");
        commanding("B", "alice");
        commanding("C", "alice");
        openTask("A", "T-A", List.of("C"));

        // 闭包 {A,C}，提交 {A,B}：B 多余且 C 缺失；缺失优先报 422
        assertThatThrownBy(() -> freeze("H-4", "alice", "bob", List.of("A", "B")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status().value()).isEqualTo(422);
                    assertThat(api.code()).isEqualTo("CLOSURE_NOT_COVERED");
                    assertThat((List<String>) api.details()).containsExactly("C");
                });
        // 失败不产生交接单
        assertThat(handoverService.listByIncident("A")).isEmpty();
    }

    // ---------- 400/403 失败分支 ----------

    @Test
    void invalidSizeOrDuplicates_returns400() {
        commanding("A", "alice");
        assertThatThrownBy(() -> freeze("H-5", "alice", "bob", List.of("A")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));

        List<String> tooMany = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> "I" + i).toList();
        tooMany.forEach(k -> {
            // 只需参数数量校验，事件无需真实存在：数量校验先于存在性校验
        });
        assertThatThrownBy(() -> handoverService.freeze("alice",
                new HandoverFreezeRequest(key(), "H-5b", "bob", tooMany)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));

        commanding("B", "alice");
        assertThatThrownBy(() -> freeze("H-6", "alice", "bob", List.of("A", "A")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    @Test
    void mixedCommanderIncident_returns403() {
        commanding("A", "alice");
        commanding("B", "carol");
        assertThatThrownBy(() -> freeze("H-7", "alice", "bob", List.of("A", "B")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
    }

    @Test
    void terminalIncident_isExtra_returns400() {
        commanding("A", "alice");
        commanding("B", "alice");
        incidentService.changeStatus("B", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("B", "alice", new StatusRequest(key(), "RESOLVED"));
        assertThatThrownBy(() -> freeze("H-8", "alice", "bob", List.of("A", "B")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    @Test
    void receiverEqualsActor_returns400() {
        commanding("A", "alice");
        commanding("B", "alice");
        assertThatThrownBy(() -> freeze("H-9", "alice", "alice", List.of("A", "B")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    @Test
    void duplicateHandoverKey_returns409() {
        commanding("A", "alice");
        commanding("B", "alice");
        freeze("H-DUP", "alice", "bob", List.of("A", "B"));
        assertThatThrownBy(() -> freeze("H-DUP", "alice", "bob", List.of("A", "B")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    // ---------- 接受失败分支：403/409 ----------

    @Test
    void acceptByWrongReceiver_returns403AndNothingChanges() {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView frozen = freeze("H-10", "alice", "bob", List.of("A", "B"));

        assertThatThrownBy(() -> accept(frozen, "carol"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));

        assertThat(handoverService.get("H-10").status()).isEqualTo("PENDING");
        assertThat(incidentService.get("A").commander()).isEqualTo("alice");
        assertThat(incidentService.get("B").commander()).isEqualTo("alice");
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_handover_snapshot_incidents", Integer.class);
        assertThat(snapshots).isZero();
    }

    @Test
    void acceptAfterIncidentChange_returns409AndRollsBack() {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView frozen = freeze("H-11", "alice", "bob", List.of("A", "B"));

        // 冻结后 alice 推进 A 到 CONTAINED：事件状态与版本变化
        incidentService.changeStatus("A", "alice", new StatusRequest(key(), "CONTAINED"));

        assertThatThrownBy(() -> accept(frozen, "bob"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));

        // 整体回滚：交接单仍 PENDING，无快照，B 未被部分接管，指挥人未变
        assertThat(handoverService.get("H-11").status()).isEqualTo("PENDING");
        assertThat(incidentService.get("B").commander()).isEqualTo("alice");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_handover_snapshot_incidents", Integer.class)).isZero();
    }

    @Test
    void pendingSingleTransferBlocksFreeze() {
        commanding("A", "alice");
        commanding("B", "alice");
        incidentService.initiateTransfer("A", "alice", new TransferRequest(key(), "carol"));
        assertThatThrownBy(() -> freeze("H-11B", "alice", "bob", List.of("A", "B")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void acceptAfterTaskCompleted_returns409() {
        commanding("A", "alice");
        commanding("B", "alice");
        openTask("A", "T-A", List.of());
        HandoverView frozen = freeze("H-12", "alice", "bob", List.of("A", "B"));

        incidentService.completeTask("A", "T-A", "alice", new TaskActionRequest(key()));

        assertThatThrownBy(() -> accept(frozen, "bob"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void acceptAfterDependencyAdded_returns409() {
        commanding("A", "alice");
        commanding("B", "alice");
        commanding("C", "alice");
        openTask("A", "T-A", List.of());
        HandoverView frozen = freeze("H-13", "alice", "bob", List.of("A", "B"));

        // 冻结后 A 新增一个带跨事件依赖的 OPEN 任务：OPEN 任务集合变化
        openTask("A", "T-A2", List.of("C"));

        assertThatThrownBy(() -> accept(frozen, "bob"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void acceptAfterEscalationAcked_returns409() {
        incidentService.report(new ReportRequest("A", "S1", "s", "r"));
        incidentService.takeover("A", "alice", new TakeoverRequest(key()));
        commanding("B", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
        incidentService.checkEscalation("A",
                new com.example.starter.incident.dto.Requests.EscalationCheckRequest(key()));

        HandoverView frozen = freeze("H-14", "alice", "bob", List.of("A", "B"));
        var incidentABefore = frozen.summary().incidents().stream()
                .filter(i -> i.incidentKey().equals("A")).findFirst().orElseThrow();
        assertThat(incidentABefore.unacknowledgedEscalationVersion()).isNotNull();

        incidentService.acknowledgeEscalation("A", "alice",
                new com.example.starter.incident.dto.Requests.EscalationAckRequest(key(), "处理中"));

        assertThatThrownBy(() -> accept(frozen, "bob"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void acceptWithWrongVersionOrSummary_returns409() {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView frozen = freeze("H-15", "alice", "bob", List.of("A", "B"));

        assertThatThrownBy(() -> handoverService.accept("bob",
                new HandoverAcceptRequest(key(), "0".repeat(64), frozen.summary())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));

        HandoverSummary tampered = new HandoverSummary(frozen.summary().fromCommander(),
                frozen.summary().toCommander(), List.of("A"), frozen.summary().incidents());
        assertThatThrownBy(() -> handoverService.accept("bob",
                new HandoverAcceptRequest(key(), frozen.handoverVersion(), tampered)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void acceptAfterIncidentReachedTerminal_returns409() {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView frozen = freeze("H-16", "alice", "bob", List.of("A", "B"));
        incidentService.changeStatus("A", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("A", "alice", new StatusRequest(key(), "RESOLVED"));

        assertThatThrownBy(() -> accept(frozen, "bob"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void acceptTwice_secondReplaySucceedsIdempotently() {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView frozen = freeze("H-17", "alice", "bob", List.of("A", "B"));
        String acceptCommandKey = key();

        HandoverView first = handoverService.accept("bob", new HandoverAcceptRequest(
                acceptCommandKey, frozen.handoverVersion(), frozen.summary()));
        HandoverView second = handoverService.accept("bob", new HandoverAcceptRequest(
                acceptCommandKey, frozen.handoverVersion(), frozen.summary()));

        assertThat(first).isEqualTo(second);
        assertThat(handoverService.get("H-17").status()).isEqualTo("ACCEPTED");
    }

    // ---------- 幂等边界 ----------

    @Test
    void freezeIdempotent_reorderSameParamsReplays() {
        commanding("A", "alice");
        commanding("B", "alice");
        openTask("A", "T-A", List.of("B"));
        String commandKey = key();

        HandoverView first = handoverService.freeze("alice", new HandoverFreezeRequest(
                commandKey, "H-18", "bob", List.of("A", "B")));
        // 集合换序：同操作、同操作者、同结构化参数
        HandoverView replay = handoverService.freeze("alice", new HandoverFreezeRequest(
                commandKey, "H-18", "bob", List.of("B", "A")));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_handovers", Integer.class)).isEqualTo(1);
    }

    @Test
    void freezeSameKeyDifferentParams_returns409() {
        commanding("A", "alice");
        commanding("B", "alice");
        String commandKey = key();
        handoverService.freeze("alice",
                new HandoverFreezeRequest(commandKey, "H-19", "bob", List.of("A", "B")));

        assertThatThrownBy(() -> handoverService.freeze("alice",
                new HandoverFreezeRequest(commandKey, "H-19", "carol", List.of("A", "B"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void failedFreezeDoesNotOccupyKey() {
        commanding("A", "alice");
        commanding("B", "alice");
        commanding("C", "alice");
        openTask("A", "T-A", List.of("C"));
        String commandKey = key();

        // 同键首次提交缺失闭包事件，422
        assertThatThrownBy(() -> handoverService.freeze("alice",
                new HandoverFreezeRequest(commandKey, "H-20", "bob", List.of("A", "B"))))
                .isInstanceOf(ApiException.class);
        // 失败不占键：同键改为恰好覆盖闭包的提交后成功
        HandoverView view = handoverService.freeze("alice",
                new HandoverFreezeRequest(commandKey, "H-20", "bob", List.of("A", "C")));
        assertThat(view.closureIncidentKeys()).containsExactly("A", "C");
    }

    @Test
    void pendingSnapshotQuery_returns409AndHistoryIsReadonly() {
        commanding("A", "alice");
        commanding("B", "alice");
        freeze("H-21", "alice", "bob", List.of("A", "B"));
        assertThatThrownBy(() -> handoverService.snapshot("H-21"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        assertThat(handoverService.listByIncident("A")).hasSize(1);
        assertThat(handoverService.listByIncident("B")).hasSize(1);
    }
}
