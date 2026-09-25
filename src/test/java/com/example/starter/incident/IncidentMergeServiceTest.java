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
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentTaskOwnershipView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.MergeRecordView;
import com.example.starter.incident.dto.Responses.MergeRecordsView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 重复事件合并服务测试：覆盖合并主流程（任务迁移、阻塞边改挂/去重/自环丢弃、
 * 期限与升级作废、版本递增、不可变合并记录）、合并前置校验、taskKey 冲突、
 * 整图环检测回滚、MERGED 终态门禁、解决门禁、链式合并归属及 commandKey 幂等语义。
 * 使用可控 Clock，期限相关断言不依赖真实睡眠。
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
        jdbc.update("DELETE FROM incident_merge_tasks");
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

    private IncidentView commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private MergeRequest mergeRequest(String mergeKey, String survivingKey, String mergedKey,
                                      long survivingVersion, long mergedVersion) {
        return new MergeRequest(key(), mergeKey, survivingKey, mergedKey,
                survivingVersion, mergedVersion);
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
    void merge_mainFlow_tasksMigratedEdgesRepointed() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-X", "bob");
        commanding("INC-Y", "carol");
        // 被并入事件任务：T-M1 无阻塞，T-M2 阻塞 INC-X
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-M1", "DB", "迁移任务1", List.of()));
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-M2", "DB", "迁移任务2", List.of("INC-X")));
        // 第三方任务阻塞被并入事件：合并后改指存续事件
        service.createTask("INC-Y", "carol",
                new TaskCreateRequest(key(), "T-Y1", "APP", "第三方任务", List.of("INC-M")));
        // 存续事件任务阻塞被并入事件：改挂后成自环，直接丢弃
        service.createTask("INC-S", "alice",
                new TaskCreateRequest(key(), "T-S1", "APP", "存续任务", List.of("INC-M")));

        MergeRecordView record = service.merge("alice",
                mergeRequest("MR-1", "INC-S", "INC-M", 0, 0));

        assertThat(record.mergeKey()).isEqualTo("MR-1");
        assertThat(record.survivingIncidentKey()).isEqualTo("INC-S");
        assertThat(record.mergedIncidentKey()).isEqualTo("INC-M");
        assertThat(record.actor()).isEqualTo("alice");
        assertThat(record.migratedTaskKeys()).containsExactly("T-M1", "T-M2");
        assertThat(record.createdAt()).isNotNull();

        // 双方版本各加一；被并入事件 MERGED 终态并记录存续事件，期限作废
        IncidentView surviving = service.get("INC-S");
        IncidentView merged = service.get("INC-M");
        assertThat(surviving.status()).isEqualTo("COMMANDING");
        assertThat(surviving.version()).isEqualTo(1);
        assertThat(merged.status()).isEqualTo("MERGED");
        assertThat(merged.version()).isEqualTo(1);
        assertThat(merged.mergedIntoIncidentKey()).isEqualTo("INC-S");
        assertThat(merged.deadlineAt()).isNull();

        // 任务全部迁移到存续事件（按落库顺序返回）；被并入事件不再持有任务
        IncidentTasksView survivingTasks = service.listTasks("INC-S");
        assertThat(survivingTasks.tasks()).extracting(TaskView::taskKey)
                .containsExactly("T-M1", "T-M2", "T-S1");
        assertThat(service.listTasks("INC-M").tasks()).isEmpty();

        // 自环边丢弃：T-S1 不再有任何阻塞
        assertThat(service.getTask("INC-S", "T-S1").blockers()).isEmpty();
        // 迁移任务的阻塞边保留：T-M2 仍阻塞 INC-X
        assertThat(service.getTask("INC-S", "T-M2").blockers())
                .extracting(b -> b.incidentKey()).containsExactly("INC-X");
        // 第三方阻塞边改指存续事件，按存续事件当前状态计算阻塞
        TaskView yTask = service.getTask("INC-Y", "T-Y1");
        assertThat(yTask.blockers()).hasSize(1);
        assertThat(yTask.blockers().get(0).incidentKey()).isEqualTo("INC-S");
        assertThat(yTask.blockers().get(0).incidentStatus()).isEqualTo("COMMANDING");
        assertThat(yTask.blockers().get(0).resolved()).isFalse();

        // 合并记录与归属查询
        MergeRecordsView merges = service.listMerges();
        assertThat(merges.merges()).containsExactly(record);
        IncidentTaskOwnershipView ownership = service.taskOwnership("INC-M");
        assertThat(ownership.status()).isEqualTo("MERGED");
        assertThat(ownership.mergedIntoIncidentKey()).isEqualTo("INC-S");
        assertThat(ownership.tasks()).extracting(t -> t.taskKey())
                .containsExactly("T-M1", "T-M2");
        assertThat(ownership.tasks()).allSatisfy(
                t -> assertThat(t.ownerIncidentKey()).isEqualTo("INC-S"));
    }

    @Test
    void merge_duplicateEdges_deduped() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // 第三方任务同时阻塞存续与被并入事件：改挂后重复边去重
        commanding("INC-X", "bob");
        service.createTask("INC-X", "bob",
                new TaskCreateRequest(key(), "T-X1", "G", "t", List.of("INC-S", "INC-M")));

        service.merge("alice", mergeRequest("MR-D", "INC-S", "INC-M", 0, 0));

        TaskView xTask = service.getTask("INC-X", "T-X1");
        assertThat(xTask.blockers()).hasSize(1);
        assertThat(xTask.blockers().get(0).incidentKey()).isEqualTo("INC-S");
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
    }

    @Test
    void merge_blockerResolution_followsSurvivingStatus() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-X", "bob");
        service.createTask("INC-X", "bob",
                new TaskCreateRequest(key(), "T-X1", "G", "t", List.of("INC-M")));
        service.merge("alice", mergeRequest("MR-B", "INC-S", "INC-M", 0, 0));

        // 存续事件未遏制：阻塞未解除，完成被拒
        assertApiStatus(() -> service.completeTask("INC-X", "T-X1", "bob",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 存续事件遏制后按当前状态解除阻塞
        service.changeStatus("INC-S", "alice", new StatusRequest(key(), "CONTAINED"));
        TaskView done = service.completeTask("INC-X", "T-X1", "bob",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void merge_validation_conflicts() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-O", "carol");
        // 两事件相同
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-V1", "INC-S", "INC-S", 0, 0)), HttpStatus.CONFLICT);
        // 当前指挥人不同
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-V2", "INC-O", "INC-M", 0, 0)), HttpStatus.CONFLICT);
        // 操作人不是当前指挥人
        assertApiStatus(() -> service.merge("bob",
                mergeRequest("MR-V3", "INC-S", "INC-M", 0, 0)), HttpStatus.CONFLICT);
        // 版本不匹配
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-V4", "INC-S", "INC-M", 1, 0)), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-V5", "INC-S", "INC-M", 0, 2)), HttpStatus.CONFLICT);
        // 事件不存在
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-V6", "INC-S", "INC-404", 0, 0)), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-V7", "INC-404", "INC-M", 0, 0)), HttpStatus.NOT_FOUND);
        // 必填字段为空
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), " ", "INC-S", "INC-M", 0L, 0L)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(key(), "MR-V8", "INC-S", "INC-M", null, 0L)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(null, "MR-V9", "INC-S", "INC-M", 0L, 0L)),
                HttpStatus.BAD_REQUEST);
        // 全部失败均不产生副作用
        assertThat(service.get("INC-S").status()).isEqualTo("COMMANDING");
        assertThat(service.get("INC-M").status()).isEqualTo("COMMANDING");
        assertThat(service.listMerges().merges()).isEmpty();
    }

    @Test
    void merge_terminalParticipants_rejected() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // RESOLVED 不能参与合并
        commanding("INC-R", "alice");
        service.changeStatus("INC-R", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-R", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-T1", "INC-R", "INC-M", 0, 0)), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-T2", "INC-S", "INC-R", 0, 0)), HttpStatus.CONFLICT);
        // CLOSED 不能参与合并
        commanding("INC-C", "alice");
        service.changeStatus("INC-C", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-C", "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-C", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-T3", "INC-S", "INC-C", 0, 0)), HttpStatus.CONFLICT);
        // REPORTED（未接管）无指挥人：409
        service.report(new ReportRequest("INC-N", "S2", "s", "r"));
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-T4", "INC-S", "INC-N", 0, 0)), HttpStatus.CONFLICT);
    }

    @Test
    void merge_taskKeyConflict_wholeMergeRejected() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-X", "bob");
        service.createTask("INC-S", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "存续任务", List.of()));
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "被并入任务", List.of()));
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "不冲突任务", List.of("INC-X")));

        assertThatThrownBy(() -> service.merge("alice",
                mergeRequest("MR-K", "INC-S", "INC-M", 0, 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly("T-1");
                });

        // 整次 409：任务、依赖边、事件状态与版本一律不变
        assertThat(service.listTasks("INC-S").tasks()).extracting(TaskView::taskKey)
                .containsExactly("T-1");
        assertThat(service.listTasks("INC-M").tasks()).extracting(TaskView::taskKey)
                .containsExactly("T-1", "T-2");
        assertThat(service.get("INC-M").status()).isEqualTo("COMMANDING");
        assertThat(service.get("INC-S").version()).isZero();
        assertThat(service.get("INC-M").version()).isZero();
        assertThat(service.listMerges().merges()).isEmpty();
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
    }

    @Test
    void merge_cycleAfterRepoint_wholeMergeRolledBack() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-X", "bob");
        // X → M（合并后改指 S），S → X 已存在：改挂后 S→X→S 成环
        service.createTask("INC-X", "bob",
                new TaskCreateRequest(key(), "T-X1", "G", "t", List.of("INC-M")));
        service.createTask("INC-S", "alice",
                new TaskCreateRequest(key(), "T-S1", "G", "t", List.of("INC-X")));

        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-C", "INC-S", "INC-M", 0, 0)), HttpStatus.CONFLICT);

        // 整次回滚：任务、依赖边、事件状态与版本一律不变
        assertThat(service.get("INC-M").status()).isEqualTo("COMMANDING");
        assertThat(service.get("INC-M").mergedIntoIncidentKey()).isNull();
        assertThat(service.get("INC-S").version()).isZero();
        assertThat(service.get("INC-M").version()).isZero();
        assertThat(service.getTask("INC-X", "T-X1").blockers())
                .extracting(b -> b.incidentKey()).containsExactly("INC-M");
        assertThat(service.listMerges().merges()).isEmpty();
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(2);
    }

    @Test
    void merge_mergedIncident_terminalGates() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        service.merge("alice", mergeRequest("MR-G", "INC-S", "INC-M", 0, 0));

        // MERGED 事件不能创建任务、交接、升级
        assertApiStatus(() -> service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-9", "G", "t", List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.initiateTransfer("INC-M", "alice",
                new TransferRequest(key(), "bob")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.checkEscalation("INC-M",
                new EscalationCheckRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // MERGED 事件不能作为新的阻塞目标
        commanding("INC-Y", "carol");
        assertApiStatus(() -> service.createTask("INC-Y", "carol",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-M"))),
                HttpStatus.CONFLICT);
        // MERGED 终态不能状态流转、不能再次接管
        assertApiStatus(() -> service.changeStatus("INC-M", "alice",
                new StatusRequest(key(), "CONTAINED")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.takeover("INC-M", "alice",
                new TakeoverRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // 同一事件最多被合并一次（MERGED 不能作为被并入方，也不能作为存续方）
        commanding("INC-Z", "alice");
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-G2", "INC-Z", "INC-M", 0, 1)), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-G3", "INC-M", "INC-Z", 1, 0)), HttpStatus.CONFLICT);
    }

    @Test
    void merge_voidsDeadlineAndPendingEscalation() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // 被并入事件遏制逾期，产生 OPEN 升级记录
        ((ControllableClock) clock).setInstant(T0.plusSeconds(16 * 60));
        service.checkEscalation("INC-M", new EscalationCheckRequest(key()));
        assertThat(service.escalationHistory("INC-M").current().status()).isEqualTo("OPEN");
        assertThat(service.get("INC-M").deadlineAt()).isNotNull();

        service.merge("alice", mergeRequest("MR-E", "INC-S", "INC-M", 0, 0));

        // 期限作废置空，OPEN 升级取消
        assertThat(service.get("INC-M").deadlineAt()).isNull();
        assertThat(service.escalationHistory("INC-M").current().status())
                .isEqualTo("CANCELLED");
        // 存续事件期限不受影响
        assertThat(service.get("INC-S").deadlineAt()).isNotNull();
    }

    @Test
    void merge_resolveGate_countsMigratedTasks() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-M1", "DB", "迁移任务", List.of()));
        service.merge("alice", mergeRequest("MR-R", "INC-S", "INC-M", 0, 0));

        service.changeStatus("INC-S", "alice", new StatusRequest(key(), "CONTAINED"));
        // 迁移来的 OPEN 任务仍阻止存续事件解决
        assertApiStatus(() -> service.changeStatus("INC-S", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);
        // 存续事件指挥人完成迁移任务后可解决
        service.completeTask("INC-S", "T-M1", "alice", new TaskActionRequest(key()));
        IncidentView resolved = service.changeStatus("INC-S", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    @Test
    void merge_chain_ownershipFollowsFinalSurvivor() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-C", "alice");
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-A1", "G", "t", List.of()));
        service.merge("alice", mergeRequest("MR-1", "INC-B", "INC-A", 0, 0));
        // 链式合并：B（已含 A 的任务，版本 1）并入 C（版本 0）
        MergeRecordView second = service.merge("alice",
                mergeRequest("MR-2", "INC-C", "INC-B", 0, 1));
        assertThat(second.migratedTaskKeys()).containsExactly("T-A1");

        // 合并记录按落库顺序稳定返回
        MergeRecordsView merges = service.listMerges();
        assertThat(merges.merges()).extracting(MergeRecordView::mergeKey)
                .containsExactly("MR-1", "MR-2");
        // 归属查询反映最终存续事件
        IncidentTaskOwnershipView ownership = service.taskOwnership("INC-A");
        assertThat(ownership.mergedIntoIncidentKey()).isEqualTo("INC-B");
        assertThat(ownership.tasks()).hasSize(1);
        assertThat(ownership.tasks().get(0).ownerIncidentKey()).isEqualTo("INC-C");
        // 环形合并链不可能：MERGED 终态不能作为存续方
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-3", "INC-A", "INC-C", 1, 1)), HttpStatus.CONFLICT);
    }

    @Test
    void merge_commandKeyIdempotency() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        service.createTask("INC-M", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        // 同键同参重放首次结果
        String commandKey = key();
        MergeRequest req = new MergeRequest(commandKey, "MR-I", "INC-S", "INC-M", 0L, 0L);
        MergeRecordView first = service.merge("alice", req);
        MergeRecordView replay = service.merge("alice", req);
        assertThat(replay).isEqualTo(first);
        assertThat(service.listMerges().merges()).hasSize(1);
        // 同键改参 → 409
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(commandKey, "MR-I2", "INC-S", "INC-M", 0L, 0L)),
                HttpStatus.CONFLICT);
        // 失败不占键：版本不匹配失败后同键可成功复用
        commanding("INC-S2", "alice");
        commanding("INC-M2", "alice");
        String failKey = key();
        assertApiStatus(() -> service.merge("alice",
                new MergeRequest(failKey, "MR-F", "INC-S2", "INC-M2", 9L, 0L)),
                HttpStatus.CONFLICT);
        MergeRecordView ok = service.merge("alice",
                new MergeRequest(failKey, "MR-F", "INC-S2", "INC-M2", 0L, 0L));
        assertThat(ok.mergeKey()).isEqualTo("MR-F");
    }

    @Test
    void merge_mergeKeyGloballyUnique() {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-S2", "alice");
        commanding("INC-M2", "alice");
        service.merge("alice", mergeRequest("MR-U", "INC-S", "INC-M", 0, 0));
        // 相同 mergeKey 用于另一对事件 → 409
        assertApiStatus(() -> service.merge("alice",
                mergeRequest("MR-U", "INC-S2", "INC-M2", 0, 0)), HttpStatus.CONFLICT);
        assertThat(service.get("INC-M2").status()).isEqualTo("COMMANDING");
        assertThat(service.listMerges().merges()).hasSize(1);
    }
}
