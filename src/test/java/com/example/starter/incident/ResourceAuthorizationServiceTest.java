package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.RenewalEntry;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskExecuteRequest;
import com.example.starter.incident.dto.Requests.TaskRenewalRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.AuthorizationExpiredDetails;
import com.example.starter.incident.dto.Responses.ExecutionLogView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ResourceAssignmentView;
import com.example.starter.incident.dto.Responses.ResourceDiagnosticsView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
import com.example.starter.incident.dto.Responses.TaskResourceHistoryView;
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
 * 资源分配与授权到期服务测试：覆盖分配主流程与校验、资源占用冲突、
 * 到期拦截（区间左闭右开）、证据固化、执行日志保留、只读不改状态与幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceAuthorizationServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ResourceAuthorizationService resourceService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_authorization_events");
        jdbc.update("DELETE FROM task_execution_logs");
        jdbc.update("DELETE FROM task_resource_assignments");
        jdbc.update("DELETE FROM resource_locks");
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
        incidentService.report(new ReportRequest(incidentKey, "S2", "资源场景", "reporter-1"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey) {
        return incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "DB", "扩容连接池", List.of()));
    }

    private ResourceAssignmentView assign(String incidentKey, String taskKey, String actor,
                                          String resourceKey, long version, Instant expiresAt) {
        return resourceService.assign(incidentKey, taskKey, actor,
                new ResourceAssignRequest(key(), resourceKey, version, expiresAt));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void assignAndExecute_mainFlow() {
        commanding("INC-R1", "alice");
        createTask("INC-R1", "alice", "T-1");
        ResourceAssignmentView assigned = assign("INC-R1", "T-1", "alice", "RES-1", 1L,
                T0.plusSeconds(600));
        assertThat(assigned.taskKey()).isEqualTo("T-1");
        assertThat(assigned.resourceKey()).isEqualTo("RES-1");
        assertThat(assigned.authorizationVersion()).isEqualTo(1L);
        assertThat(assigned.assignedBy()).isEqualTo("alice");
        assertThat(assigned.assignedAt()).isEqualTo(T0);
        assertThat(assigned.expiresAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(assigned.expired()).isFalse();
        assertThat(assigned.taskStatus()).isEqualTo("OPEN");

        // 任务视图携带租约信息
        TaskView view = incidentService.getTask("INC-R1", "T-1");
        assertThat(view.resourceKey()).isEqualTo("RES-1");
        assertThat(view.authorizationVersion()).isEqualTo(1L);
        assertThat(view.assignedBy()).isEqualTo("alice");
        assertThat(view.authorizationExpired()).isFalse();

        // 开始 → 继续 → 完成，执行日志按序保留
        TaskView started = incidentService.startTask("INC-R1", "T-1", "alice",
                new TaskStartRequest(key(), "开始处理"));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        incidentService.executeTask("INC-R1", "T-1", "alice",
                new TaskExecuteRequest(key(), "已切换备用链路"));
        TaskView done = incidentService.completeTask("INC-R1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");

        TaskResourceHistoryView history = resourceService.taskResourceHistory("INC-R1", "T-1");
        assertThat(history.current().resourceKey()).isEqualTo("RES-1");
        assertThat(history.events()).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN");
        ResourceEventView assignEvent = history.events().get(0);
        assertThat(assignEvent.oldAuthorizationVersion()).isNull();
        assertThat(assignEvent.newAuthorizationVersion()).isEqualTo(1L);
        assertThat(assignEvent.actor()).isEqualTo("alice");
        assertThat(assignEvent.attemptedAction()).isNull();
        assertThat(assignEvent.occurredAt()).isEqualTo(T0);
        assertThat(history.executionLogs()).extracting(ExecutionLogView::action)
                .containsExactly("START", "CONTINUE", "COMPLETE");
        assertThat(history.executionLogs().get(1).note()).isEqualTo("已切换备用链路");

        // 诊断视图：未到期、仍占用
        ResourceDiagnosticsView diagnostics = resourceService.diagnostics("INC-R1");
        assertThat(diagnostics.now()).isEqualTo(T0);
        assertThat(diagnostics.assignments()).hasSize(1);
        assertThat(diagnostics.assignments().get(0).expired()).isFalse();
        assertThat(diagnostics.assignments().get(0).taskStatus()).isEqualTo("DONE");
    }

    @Test
    void assign_validation() {
        commanding("INC-V1", "alice");
        createTask("INC-V1", "alice", "T-1");
        // 非空/取值校验
        assertApiStatus(() -> resourceService.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), " ", 1L, T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", null, T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 0L, T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1L, null)),
                HttpStatus.BAD_REQUEST);
        // 到期时刻必须晚于分配时刻：等于或早于均 400，details 返回实际值与要求
        assertThatThrownBy(() -> resourceService.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1L, T0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.details().toString()).contains("expiresAt > assignedAt");
                });
        assertApiStatus(() -> resourceService.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1L, T0.minusSeconds(1))),
                HttpStatus.BAD_REQUEST);
        // 任务/事件不存在、非指挥人
        assertApiStatus(() -> assign("INC-V1", "T-9", "alice", "RES-1", 1L, T0.plusSeconds(60)),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> assign("INC-404", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(60)),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> assign("INC-V1", "T-1", "bob", "RES-1", 1L, T0.plusSeconds(60)),
                HttpStatus.CONFLICT);
        // 成功分配后：重复分配 409
        assign("INC-V1", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(60));
        assertApiStatus(() -> assign("INC-V1", "T-1", "alice", "RES-2", 1L, T0.plusSeconds(60)),
                HttpStatus.CONFLICT);
        // 终态任务不可分配
        incidentService.completeTask("INC-V1", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> assign("INC-V1", "T-1", "alice", "RES-3", 1L, T0.plusSeconds(60)),
                HttpStatus.CONFLICT);
    }

    @Test
    void assign_resourceOccupiedByOtherIncident() {
        commanding("INC-OA", "alice");
        commanding("INC-OB", "bob");
        createTask("INC-OA", "alice", "T-1");
        createTask("INC-OB", "bob", "T-2");
        createTask("INC-OA", "alice", "T-3");
        assign("INC-OA", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        // 其他事件占用同一资源：409，details 返回占用信息
        assertThatThrownBy(() -> assign("INC-OB", "T-2", "bob", "RES-1", 1L, T0.plusSeconds(300)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.details().toString()).contains("RES-1");
                });
        // 同一事件的另一任务可共享同一资源
        ResourceAssignmentView shared = assign("INC-OA", "T-3", "alice", "RES-1", 1L,
                T0.plusSeconds(300));
        assertThat(shared.resourceKey()).isEqualTo("RES-1");
        // 原租约到期（区间右开）后，其他事件可占用
        ((ControllableClock) clock).setInstant(T0.plusSeconds(300));
        ResourceAssignmentView taken = assign("INC-OB", "T-2", "bob", "RES-1", 1L,
                T0.plusSeconds(600));
        assertThat(taken.assignedBy()).isEqualTo("bob");
    }

    @Test
    void expiry_interceptsExecutionAndComplete() {
        commanding("INC-E1", "alice");
        createTask("INC-E1", "alice", "T-1");
        assign("INC-E1", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        incidentService.startTask("INC-E1", "T-1", "alice", new TaskStartRequest(key(), null));

        // 区间右开：now == expiresAt 即到期
        ((ControllableClock) clock).setInstant(T0.plusSeconds(300));
        String failedKey = key();
        assertThatThrownBy(() -> incidentService.executeTask("INC-E1", "T-1", "alice",
                new TaskExecuteRequest(failedKey, "继续")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.details()).isInstanceOfSatisfying(
                            AuthorizationExpiredDetails.class, d -> {
                                assertThat(d.taskKey()).isEqualTo("T-1");
                                assertThat(d.resourceKey()).isEqualTo("RES-1");
                                assertThat(d.authorizationVersion()).isEqualTo(1L);
                                assertThat(d.expiresAt()).isEqualTo(T0.plusSeconds(300));
                                assertThat(d.detectedAt()).isEqualTo(T0.plusSeconds(300));
                                assertThat(d.attemptedAction()).isEqualTo("CONTINUE");
                            });
                });
        // 任务已转入 AUTHORIZATION_EXPIRED（持久化），执行日志保留
        TaskView view = incidentService.getTask("INC-E1", "T-1");
        assertThat(view.status()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(view.authorizationExpired()).isTrue();
        String stored = jdbc.queryForObject(
                "SELECT status FROM incident_tasks WHERE task_key = 'T-1'", String.class);
        assertThat(stored).isEqualTo("AUTHORIZATION_EXPIRED");
        String preExpiry = jdbc.queryForObject(
                "SELECT pre_expiry_status FROM incident_tasks WHERE task_key = 'T-1'", String.class);
        assertThat(preExpiry).isEqualTo("IN_PROGRESS");
        TaskResourceHistoryView history = resourceService.taskResourceHistory("INC-E1", "T-1");
        assertThat(history.events()).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXPIRY_DETECTED", "EXECUTION_BLOCKED");
        assertThat(history.executionLogs()).extracting(ExecutionLogView::action)
                .containsExactly("START");

        // 到期后禁止完成（再次拦截，只追加 EXECUTION_BLOCKED）
        assertApiStatus(() -> incidentService.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 到期后禁止再次分配
        assertApiStatus(() -> assign("INC-E1", "T-1", "alice", "RES-9", 1L, T0.plusSeconds(900)),
                HttpStatus.CONFLICT);
        history = resourceService.taskResourceHistory("INC-E1", "T-1");
        assertThat(history.events()).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXPIRY_DETECTED", "EXECUTION_BLOCKED",
                        "EXECUTION_BLOCKED");

        // 失败不占键：被拦截的 execute 的 commandKey 在续期后可重用成功
        resourceService.renew("INC-E1", "carol", new TaskRenewalRequest(key(),
                List.of(new RenewalEntry("T-1", 2L, T0.plusSeconds(900)))));
        TaskView executed = incidentService.executeTask("INC-E1", "T-1", "alice",
                new TaskExecuteRequest(failedKey, "继续"));
        assertThat(executed.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void expiry_boundaryLeftClosedRightOpen() {
        commanding("INC-B1", "alice");
        createTask("INC-B1", "alice", "T-1");
        createTask("INC-B1", "alice", "T-2");
        assign("INC-B1", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        assign("INC-B1", "T-2", "alice", "RES-2", 1L, T0.plusSeconds(300));
        // 到期前 1 秒：仍可完成（区间内）
        ((ControllableClock) clock).setInstant(T0.plusSeconds(299));
        TaskView done = incidentService.completeTask("INC-B1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        // 恰到期时刻：右开，已到期
        ((ControllableClock) clock).setInstant(T0.plusSeconds(300));
        assertApiStatus(() -> incidentService.completeTask("INC-B1", "T-2", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void reads_doNotMutateState() {
        commanding("INC-RD", "alice");
        createTask("INC-RD", "alice", "T-1");
        assign("INC-RD", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        // 读取按时钟判定为到期，但不写回
        TaskView view = incidentService.getTask("INC-RD", "T-1");
        assertThat(view.status()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(view.authorizationExpired()).isTrue();
        ResourceDiagnosticsView diagnostics = resourceService.diagnostics("INC-RD");
        assertThat(diagnostics.assignments().get(0).expired()).isTrue();
        assertThat(diagnostics.assignments().get(0).taskStatus())
                .isEqualTo("AUTHORIZATION_EXPIRED");
        resourceService.taskResourceHistory("INC-RD", "T-1");
        resourceService.listEvents("INC-RD");
        // 存储状态与证据未被读取改变
        String stored = jdbc.queryForObject(
                "SELECT status FROM incident_tasks WHERE task_key = 'T-1'", String.class);
        assertThat(stored).isEqualTo("OPEN");
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_authorization_events", Integer.class);
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    void expiredTask_canBeCancelled() {
        commanding("INC-X1", "alice");
        createTask("INC-X1", "alice", "T-1");
        assign("INC-X1", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        TaskView cancelled = incidentService.cancelTask("INC-X1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        // 取消后资源释放：其他事件可占用
        commanding("INC-X2", "bob");
        createTask("INC-X2", "bob", "T-9");
        ResourceAssignmentView taken = assign("INC-X2", "T-9", "bob", "RES-1", 1L,
                T0.plusSeconds(900));
        assertThat(taken.resourceKey()).isEqualTo("RES-1");
    }

    @Test
    void assign_commandKeyIdempotency() {
        commanding("INC-I1", "alice");
        createTask("INC-I1", "alice", "T-1");
        String commandKey = key();
        ResourceAssignmentView first = resourceService.assign("INC-I1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "RES-1", 1L, T0.plusSeconds(600)));
        ResourceAssignmentView replay = resourceService.assign("INC-I1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "RES-1", 1L, T0.plusSeconds(600)));
        assertThat(replay).isEqualTo(first);
        Integer assignmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_resource_assignments", Integer.class);
        assertThat(assignmentCount).isEqualTo(1);
        // 同键改参 → 409
        assertApiStatus(() -> resourceService.assign("INC-I1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "RES-2", 1L, T0.plusSeconds(600))),
                HttpStatus.CONFLICT);
    }

    @Test
    void execute_stateValidation() {
        commanding("INC-S1", "alice");
        createTask("INC-S1", "alice", "T-1");
        // 未开始不能继续执行
        assertApiStatus(() -> incidentService.executeTask("INC-S1", "T-1", "alice",
                new TaskExecuteRequest(key(), "n")), HttpStatus.CONFLICT);
        // 重复开始 409
        incidentService.startTask("INC-S1", "T-1", "alice", new TaskStartRequest(key(), null));
        assertApiStatus(() -> incidentService.startTask("INC-S1", "T-1", "alice",
                new TaskStartRequest(key(), null)), HttpStatus.CONFLICT);
        // 继续执行说明必填
        assertApiStatus(() -> incidentService.executeTask("INC-S1", "T-1", "alice",
                new TaskExecuteRequest(key(), " ")), HttpStatus.BAD_REQUEST);
        // 无资源任务的开始/继续/完成不做到期校验
        incidentService.executeTask("INC-S1", "T-1", "alice", new TaskExecuteRequest(key(), "n"));
        TaskView done = incidentService.completeTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }
}
