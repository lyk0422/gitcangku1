package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.MergeRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.MergeListView;
import com.example.starter.incident.dto.Responses.MergeRecordView;
import com.example.starter.incident.dto.Responses.MergeView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.UnfinishedTaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 重复事件合并服务测试：覆盖合并主流程（任务迁移、阻塞边改挂/去重/自边丢弃）、
 * 任务键冲突与环检测整体回滚、前置校验、MERGED 终态门禁、遏制期限与待处理升级作废、
 * 版本裁决、解决门禁、commandKey/mergeKey 幂等与合并记录查询。
 * 使用可控 Clock，期限与升级相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class IncidentMergeServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_merges");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private static String mergeKey() {
        return "MRG-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        return commanding(incidentKey, "S2", commander);
    }

    private IncidentView commanding(String incidentKey, String severity, String commander) {
        service.report(new ReportRequest(incidentKey, severity, "核心链路故障", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private MergeView merge(String actor, String surviving, String merged, long sv, long mv) {
        return service.merge(actor,
                new MergeRequest(key(), mergeKey(), surviving, merged, sv, mv));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> details(ApiException e) {
        return (List<Object>) e.details();
    }

    @Test
    void merge_mainFlow_tasksMigratedAndEdgesRewired() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-X", "bob");
        commanding("INC-Y", "dave");
        // 存续事件任务：指向被并入事件（合并改挂后指向自身，应丢弃）
        service.createTask("INC-S", "alice",
                new TaskCreateRequest(key(), "TS-1", "G", "存续任务", List.of("INC-M")));
        // 被并入事件任务：指向第四方（迁移后保留）
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "TM-1", "G", "迁移任务", List.of("INC-Y")));
        // 第三方任务：同时指向被并入事件与存续事件（改挂后与已有边重复，应去重）
        service.createTask("INC-X", "bob",
                new TaskCreateRequest(key(), "TX-1", "G", "第三方任务", List.of("INC-M", "INC-S")));

        MergeView view = merge("alice", "INC-S", "INC-M", 0L, 0L);

        assertThat(view.survivingIncidentKey()).isEqualTo("INC-S");
        assertThat(view.mergedIncidentKey()).isEqualTo("INC-M");
        assertThat(view.survivingVersion()).isEqualTo(1L);
        assertThat(view.mergedVersion()).isEqualTo(1L);
        assertThat(view.movedTaskKeys()).containsExactly("TM-1");
        assertThat(view.actor()).isEqualTo("alice");
        assertThat(view.mergedAt()).isNotNull();

        // 被并入事件进入 MERGED 终态并记录存续事件，版本加一
        IncidentView merged = service.get("INC-M");
        assertThat(merged.status()).isEqualTo("MERGED");
        assertThat(merged.mergedIntoIncidentKey()).isEqualTo("INC-S");
        assertThat(merged.version()).isEqualTo(1L);
        assertThat(service.get("INC-S").version()).isEqualTo(1L);

        // 任务归属：迁移任务归存续事件，originIncidentKey 记录最初来源
        List<TaskView> survivingTasks = service.listTasks("INC-S").tasks();
        assertThat(survivingTasks).extracting(TaskView::taskKey)
                .containsExactly("TS-1", "TM-1");
        TaskView ts1 = survivingTasks.get(0);
        assertThat(ts1.originIncidentKey()).isNull();
        assertThat(ts1.blockers()).isEmpty();
        TaskView tm1 = survivingTasks.get(1);
        assertThat(tm1.originIncidentKey()).isEqualTo("INC-M");
        assertThat(tm1.blockers()).extracting("incidentKey").containsExactly("INC-Y");
        // 被并入事件下已无任务
        assertThat(service.listTasks("INC-M").tasks()).isEmpty();

        // 第三方边改挂存续事件并去重：TX-1 只剩一条指向 INC-S 的边
        TaskView tx1 = service.getTask("INC-X", "TX-1");
        assertThat(tx1.blockers()).extracting("incidentKey").containsExactly("INC-S");

        // 不可变合并记录已写入，可按 mergeKey 与列表查询
        MergeRecordView record = service.getMerge(view.mergeKey());
        assertThat(record.mergeKey()).isEqualTo(view.mergeKey());
        assertThat(record.survivingIncidentKey()).isEqualTo("INC-S");
        assertThat(record.mergedIncidentKey()).isEqualTo("INC-M");
        assertThat(record.actor()).isEqualTo("alice");
        assertThat(service.listMerges().merges()).containsExactly(record);
    }

    @Test
    void merge_selfEdgeFromMigratedTaskDropped() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // 被并入事件任务指向存续事件：迁移后成为指向自身的边，应直接丢弃
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "TM-1", "G", "迁移任务", List.of("INC-S")));

        MergeView view = merge("alice", "INC-S", "INC-M", 0L, 0L);
        assertThat(view.movedTaskKeys()).containsExactly("TM-1");

        TaskView tm1 = service.getTask("INC-S", "TM-1");
        assertThat(tm1.originIncidentKey()).isEqualTo("INC-M");
        assertThat(tm1.blockers()).isEmpty();
        // 全库不再存在指向自身的边
        Integer selfEdges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers b"
                        + " JOIN incident_tasks t ON t.id = b.task_id"
                        + " WHERE t.incident_id = b.blocker_incident_id", Integer.class);
        assertThat(selfEdges).isZero();
    }

    @Test
    void merge_taskKeyConflict_atomicRollback() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        service.createTask("INC-S", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "存续任务", List.of()));
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "冲突任务", List.of()));
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "TM-9", "G", "不冲突任务", List.of()));

        String commandKey = key();
        String mergeKey = mergeKey();
        assertThatThrownBy(() -> service.merge("alice",
                new MergeRequest(commandKey, mergeKey, "INC-S", "INC-M", 0L, 0L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly("T-1");
                });

        // 整次回滚：任务、事件状态、版本、合并记录均不变
        assertThat(service.listTasks("INC-M").tasks()).hasSize(2);
        assertThat(service.listTasks("INC-S").tasks()).hasSize(1);
        IncidentView merged = service.get("INC-M");
        assertThat(merged.status()).isEqualTo("COMMANDING");
        assertThat(merged.version()).isEqualTo(0L);
        assertThat(service.get("INC-S").version()).isEqualTo(0L);
        assertThat(service.listMerges().merges()).isEmpty();
    }

    @Test
    void merge_taskKeyConflict_failureDoesNotConsumeKeys() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        service.createTask("INC-S", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "存续任务", List.of()));
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "冲突任务", List.of()));

        String commandKey = key();
        String mergeKey = mergeKey();
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(commandKey, mergeKey, "INC-S", "INC-M", 0L, 0L)),
                HttpStatus.CONFLICT);
        // 失败不占键：command_keys 与 incident_merges 均无记录
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isZero();
        assertThat(service.listMerges().merges()).isEmpty();
        // 同一 commandKey/mergeKey 可用于后续成功请求（无冲突的另一对事件）
        commanding("INC-S2", "alice");
        commanding("INC-M2", "alice");
        MergeView ok = service.merge("alice",
                new MergeRequest(commandKey, mergeKey, "INC-S2", "INC-M2", 0L, 0L));
        assertThat(ok.survivingIncidentKey()).isEqualTo("INC-S2");
        assertThat(service.get("INC-M2").status()).isEqualTo("MERGED");
    }

    @Test
    void merge_inboundEdgeCycle_rejectedAtomically() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-C", "carol");
        // INC-A → INC-C，INC-C → INC-B；合并 INC-B 入 INC-A 后 INC-C→INC-B 改挂为
        // INC-C→INC-A，形成 INC-A→INC-C→INC-A 环
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "TA", "G", "t", List.of("INC-C")));
        service.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "TC", "G", "t", List.of("INC-B")));

        assertApiStatus(() -> merge("alice", "INC-A", "INC-B", 0L, 0L), HttpStatus.CONFLICT);

        // 整次回滚：任务未迁移、边未改挂、状态与版本不变、无合并记录
        assertThat(service.listTasks("INC-B").tasks()).hasSize(0);
        assertThat(service.listTasks("INC-A").tasks()).hasSize(1);
        assertThat(service.getTask("INC-C", "TC").blockers())
                .extracting("incidentKey").containsExactly("INC-B");
        assertThat(service.get("INC-B").status()).isEqualTo("COMMANDING");
        assertThat(service.get("INC-B").version()).isEqualTo(0L);
        assertThat(service.get("INC-A").version()).isEqualTo(0L);
        assertThat(service.listMerges().merges()).isEmpty();
    }

    @Test
    void merge_migratedTaskEdgeCycle_rejectedAtomically() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-C", "carol");
        // INC-B 的任务指向 INC-C，INC-C 指向 INC-A；INC-B 任务迁入 INC-A 后形成
        // INC-A→INC-C 与已有 INC-C→INC-A 的环
        service.createTask("INC-B", "alice",
                new TaskCreateRequest(key(), "TB", "G", "t", List.of("INC-C")));
        service.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "TC", "G", "t", List.of("INC-A")));

        assertApiStatus(() -> merge("alice", "INC-A", "INC-B", 0L, 0L), HttpStatus.CONFLICT);

        assertThat(service.listTasks("INC-B").tasks()).hasSize(1);
        assertThat(service.listTasks("INC-A").tasks()).isEmpty();
        assertThat(service.get("INC-B").status()).isEqualTo("COMMANDING");
        assertThat(service.listMerges().merges()).isEmpty();
    }

    @Test
    void merge_preconditions() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // 存续与被并入相同 → 409
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-S", 0L, 0L)),
                HttpStatus.CONFLICT);
        // 事件不存在 → 404
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-404", 0L, 0L)),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-404", "INC-M", 0L, 0L)),
                HttpStatus.NOT_FOUND);
        // 非空校验 → 400
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(null, mergeKey(), "INC-S", "INC-M", 0L, 0L)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), " ", "INC-S", "INC-M", 0L, 0L)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-M", null, 0L)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-M", 0L, null)),
                HttpStatus.BAD_REQUEST);
        // 操作者不是当前指挥人 → 409
        assertApiStatus(() -> service.merge("bob",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-M", 0L, 0L)),
                HttpStatus.CONFLICT);
        // 双方指挥人不同 → 409
        commanding("INC-N", "carol");
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-N", 0L, 0L)),
                HttpStatus.CONFLICT);
        // 未接管事件无指挥人 → 409
        service.report(new ReportRequest("INC-R", "S2", "s", "r"));
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-R", 0L, 0L)),
                HttpStatus.CONFLICT);
        // 版本不匹配 → 409
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-M", 1L, 0L)),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey(), "INC-S", "INC-M", 0L, 3L)),
                HttpStatus.CONFLICT);
        // 全部失败后状态不变
        assertThat(service.get("INC-S").status()).isEqualTo("COMMANDING");
        assertThat(service.get("INC-M").status()).isEqualTo("COMMANDING");
        assertThat(service.listMerges().merges()).isEmpty();
    }

    @Test
    void merge_terminalStatesRejected() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // RESOLVED 的被并入事件 → 409（状态流转不改变版本号，仍为 0）
        service.changeStatus("INC-M", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-M", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> merge("alice", "INC-S", "INC-M", 0L, 0L), HttpStatus.CONFLICT);
        // CLOSED 的存续事件 → 409
        commanding("INC-S2", "alice");
        service.changeStatus("INC-S2", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-S2", "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-S2", "alice", new StatusRequest(key(), "CLOSED"));
        commanding("INC-M2", "alice");
        assertApiStatus(() -> merge("alice", "INC-S2", "INC-M2", 0L, 0L), HttpStatus.CONFLICT);
        assertThat(service.listMerges().merges()).isEmpty();
    }

    @Test
    void merge_mergedIncidentTerminalGates() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // 合并前发起待接受交接，合并后接受应被拒绝
        service.initiateTransfer("INC-M", "alice", new TransferRequest(key(), "bob"));
        merge("alice", "INC-S", "INC-M", 0L, 0L);

        // MERGED 终态：不能创建任务、发起/接受交接、状态流转、接管、升级
        assertApiStatus(() -> service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.initiateTransfer("INC-M", "alice",
                new TransferRequest(key(), "bob")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.acceptTransfer("INC-M", "bob",
                new TransferAcceptRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.changeStatus("INC-M", "alice",
                new StatusRequest(key(), "CONTAINED")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.takeover("INC-M", "alice", new TakeoverRequest(key())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // MERGED 不能作为新的阻塞目标
        assertApiStatus(() -> service.createTask("INC-S", "alice",
                new TaskCreateRequest(key(), "T-9", "G", "t", List.of("INC-M"))),
                HttpStatus.CONFLICT);
        // 升级检查不再产生新记录
        ((ControllableClock) clock).advanceSeconds(3600);
        assertThat(service.checkEscalation("INC-M", new EscalationCheckRequest(key()))
                .history()).isEmpty();
        // 同一事件最多被合并一次：MERGED 不能再作为被并入或存续事件
        commanding("INC-T", "alice");
        assertApiStatus(() -> merge("alice", "INC-T", "INC-M", 0L, 1L), HttpStatus.CONFLICT);
        assertApiStatus(() -> merge("alice", "INC-M", "INC-T", 1L, 0L), HttpStatus.CONFLICT);
    }

    @Test
    void merge_deadlineAndOpenEscalationVoided() {
        commanding("INC-S", "alice");
        commanding("INC-M", "S1", "alice");
        // 推进时钟越过 S1 遏制期限（5 分钟），触发 OPEN 升级
        ((ControllableClock) clock).advanceSeconds(301);
        assertThat(service.checkEscalation("INC-M", new EscalationCheckRequest(key()))
                .current().status()).isEqualTo("OPEN");
        assertThat(service.get("INC-M").deadlineAt()).isNotNull();

        merge("alice", "INC-S", "INC-M", 0L, 0L);

        // 遏制期限作废置空，待处理升级取消
        IncidentView merged = service.get("INC-M");
        assertThat(merged.status()).isEqualTo("MERGED");
        assertThat(merged.deadlineAt()).isNull();
        var history = service.escalationHistory("INC-M");
        assertThat(history.deadlineAt()).isNull();
        assertThat(history.current().status()).isEqualTo("CANCELLED");
    }

    @Test
    void merge_resolveGateCountsMigratedTasks() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "TM-1", "DB", "迁移任务", List.of()));
        service.changeStatus("INC-S", "alice", new StatusRequest(key(), "CONTAINED"));
        merge("alice", "INC-S", "INC-M", 0L, 0L);

        // 迁移后仍有 OPEN 任务：存续事件不能进入 RESOLVED
        assertThatThrownBy(() -> service.changeStatus("INC-S", "alice",
                new StatusRequest(key(), "RESOLVED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e))
                            .containsExactly(new UnfinishedTaskView("DB", "TM-1"));
                });
        // 迁移任务完成后可解决
        service.completeTask("INC-S", "TM-1", "alice", new TaskActionRequest(key()));
        IncidentView resolved = service.changeStatus("INC-S", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    @Test
    void merge_blockingComputedBySurvivingStatus() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-X", "bob");
        service.createTask("INC-X", "bob",
                new TaskCreateRequest(key(), "TX-1", "G", "t", List.of("INC-M")));
        merge("alice", "INC-S", "INC-M", 0L, 0L);

        // 已有引用改挂存续事件，阻塞按存续事件当前状态计算
        TaskView before = service.getTask("INC-X", "TX-1");
        assertThat(before.blockers()).extracting("incidentKey").containsExactly("INC-S");
        assertThat(before.blockers().get(0).resolved()).isFalse();
        service.changeStatus("INC-S", "alice", new StatusRequest(key(), "CONTAINED"));
        TaskView after = service.getTask("INC-X", "TX-1");
        assertThat(after.blockers().get(0).incidentStatus()).isEqualTo("CONTAINED");
        assertThat(after.blockers().get(0).resolved()).isTrue();
        // 阻塞解除后可完成
        TaskView done = service.completeTask("INC-X", "TX-1", "bob", new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void merge_commandKeyIdempotency() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        String commandKey = key();
        String mergeKey = mergeKey();
        MergeRequest req = new MergeRequest(commandKey, mergeKey, "INC-S", "INC-M", 0L, 0L);

        MergeView first = service.merge("alice", req);
        // 同键同参重放首次结果，不产生第二条合并记录
        MergeView replay = service.merge("alice", req);
        assertThat(replay).isEqualTo(first);
        assertThat(service.listMerges().merges()).hasSize(1);
        // 同键异参 → 409
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(commandKey, mergeKey(), "INC-S", "INC-M", 0L, 0L)),
                HttpStatus.CONFLICT);
        // mergeKey 全局唯一：换 commandKey 复用同一 mergeKey → 409
        commanding("INC-S2", "alice");
        commanding("INC-M2", "alice");
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), mergeKey, "INC-S2", "INC-M2", 0L, 0L)),
                HttpStatus.CONFLICT);
        assertThat(service.listMerges().merges()).hasSize(1);
    }

    @Test
    void merge_linearChainAllowed_originPreserved() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-C", "alice");
        service.createTask("INC-B", "alice",
                new TaskCreateRequest(key(), "TB-1", "G", "t", List.of()));

        // 线性合并链：B 并入 A 后，A 再并入 C
        merge("alice", "INC-A", "INC-B", 0L, 0L);
        MergeView second = merge("alice", "INC-C", "INC-A", 0L, 1L);
        assertThat(second.movedTaskKeys()).containsExactly("TB-1");

        // 任务最终归属 INC-C，来源仍记录首次迁移前的 INC-B
        TaskView task = service.getTask("INC-C", "TB-1");
        assertThat(task.originIncidentKey()).isEqualTo("INC-B");
        assertThat(service.get("INC-B").mergedIntoIncidentKey()).isEqualTo("INC-A");
        assertThat(service.get("INC-A").mergedIntoIncidentKey()).isEqualTo("INC-C");
        assertThat(service.listMerges().merges()).hasSize(2);
    }

    @Test
    void merge_queriesStableOrderingAndNotFound() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-C", "alice");
        commanding("INC-D", "alice");
        MergeView first = merge("alice", "INC-A", "INC-B", 0L, 0L);
        MergeView second = merge("alice", "INC-C", "INC-D", 0L, 0L);

        // 合并记录按落库顺序稳定返回
        MergeListView list = service.listMerges();
        assertThat(list.merges()).extracting(MergeRecordView::mergeKey)
                .containsExactly(first.mergeKey(), second.mergeKey());
        // 不存在的 mergeKey → 404
        assertApiStatus(() -> service.getMerge("MRG-404"), HttpStatus.NOT_FOUND);
    }
}
